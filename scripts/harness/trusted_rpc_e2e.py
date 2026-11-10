"""Owned local REST/auth/RPC/RLS/outbox/Kafka acceptance. Never targets shared stage.

Reuses the signed run-state, build lock and ownership-checked Docker cleanup from e2e.py.
Private material is generated per run and mounted separately for each service.
"""
import argparse
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import threading
import time
import uuid

import e2e as e

MODULES = ('auth-service', 'project-service', 'workflow-service', 'issue-service',
           'api-gateway', 'tenant-service')
IDENTITIES = MODULES + ('notification-service', 'admin-service')


def instant(delta=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=delta)).isoformat()


def wait_protected_read(client, path, expected):
    """Read-only readiness for lazy mTLS/control channels; never retry a mutation."""
    deadline, attempts = time.monotonic() + 45, 0
    while time.monotonic() < deadline:
        attempts += 1
        try:
            status = client.request('GET', path).status
        except e.Blocked:
            status = 503
        if time.monotonic() >= deadline:
            break
        if status == expected:
            return attempts
        e.require(status == 503, 'Protected readiness returned unexpected status %s' % status)
        time.sleep(1)
    raise e.Blocked('Protected RPC readiness deadline exceeded')


class TrustedLocal(e.Local):
    modules = MODULES
    identities = IDENTITIES

    def configure_service(self, name, env, props):
        pass

    def prepare_extra_databases(self):
        pass

    def __init__(self, root, store, state):
        super().__init__(root, store, state)
        self.material = self.dir / 'material'
        self.material.mkdir(mode=0o700)
        self.public = self.material / 'public'
        self.public.mkdir(mode=0o700)
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.snapshot = {'version': 1, 'generation': 0, 'environment': 'test',
                         'services': {}, 'keys': [], 'edges': []}
        self.refresh_error = None

    def command(self, *args, input_text=None):
        return e.run_command(list(args), self.root, 120, input_text)

    def openssl(self, *args):
        binary = next((p for p in ('/opt/homebrew/opt/openssl@3/bin/openssl',
                                   '/usr/local/opt/openssl@3/bin/openssl') if Path(p).is_file()), 'openssl')
        return self.command(binary, *args)

    def key(self, name, kid):
        directory = self.material / name
        key = directory / (kid + '.signing.pem')
        public = directory / (kid + '.public.der')
        self.command('java', str(self.root / 'scripts/harness/RpcFixtureKey.java'), 'generate',
                     str(key), str(directory / (kid + '.private.der')), str(public))
        self.snapshot['keys'].append({'issuer': name, 'kid': kid,
            'publicKey': base64.b64encode(public.read_bytes()).decode(),
            'notBefore': instant(-60), 'notAfter': instant(3600), 'revoked': False})
        return key

    def refresh(self):
        with self.lock:
            self.snapshot['generation'] += 1
            self.snapshot['issuedAt'], self.snapshot['validUntil'] = instant(), instant(110)
            e.private_json(self.public / 'trust.json', self.snapshot)

    def activate(self, name, kid):
        private = self.material / name / (kid + '.private.der')
        e.private_json(self.material / name / 'active.signing.json',
                       {'kid': kid, 'privateKey': base64.b64encode(private.read_bytes()).decode()})

    def confirm_delivery(self):
        generation = self.snapshot['generation']
        for name in self.modules:
            document = json.loads(self.compose('exec', '-T', name, 'cat', '/run/trust/trust.json'))
            e.require(document['generation'] >= generation, 'Public trust delivery is incomplete')

    def refresh_loop(self):
        while not self.stop.wait(20):
            try:
                self.refresh()
            except Exception:
                self.refresh_error = True
                return

    def edge(self, issuer, audience, rpc, action, kinds, scopes, purpose='USER', mode='BOUND'):
        self.snapshot['edges'].append({'issuer': issuer, 'audience': audience, 'rpc': rpc,
            'action': action, 'kinds': kinds, 'scopes': scopes, 'purpose': purpose,
            'serviceIdentities': ['auth-service'] if purpose == 'membership' else [],
            'tenantMode': mode, 'tenants': []})

    def prepare(self):
        self.state['dockerDaemon'] = self.command('docker', 'info', '--format', '{{.ID}}').strip()
        self.state['composeProject'] = self.project
        self.store.save(self.state)
        with e.mutex(self.store.directory / '.build-lock', wait=True):
            e.run_command(['./mvnw', '-B', '-ntp', '-pl', ','.join(self.modules), '-am',
                           'package', '-DskipTests'], self.root, 1800)
            jars = self.dir / 'jars'
            jars.mkdir()
            for module in self.modules:
                shutil.copyfile(e.packaged_jar(self.root, module), jars / (module + '.jar'))
            self.state['jarRevisions'] = {m: hashlib.sha256((jars / (m + '.jar')).read_bytes()).hexdigest()
                                          for m in self.modules}
        ca = self.material / 'ca'
        ca.mkdir(mode=0o700)
        self.openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(ca / 'ca.key'),
                     '-out', str(ca / 'ca.crt'), '-days', '1', '-subj', '/CN=owned-trusted-rpc')
        for name in self.identities:
            directory = self.material / name
            directory.mkdir(mode=0o700)
            shutil.copyfile(ca / 'ca.crt', directory / 'ca.crt')
            self.openssl('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(directory / 'tls.key'),
                         '-out', str(directory / 'tls.csr'), '-subj', '/CN=' + name)
            (directory / 'tls.ext').write_text('subjectAltName=DNS:' + name + ',DNS:localhost,URI:spiffe://taska/test/'
                                               + name + '\nextendedKeyUsage=serverAuth,clientAuth\n')
            self.openssl('x509', '-req', '-in', str(directory / 'tls.csr'), '-CA', str(ca / 'ca.crt'),
                         '-CAkey', str(ca / 'ca.key'), '-CAcreateserial', '-out', str(directory / 'tls.crt'),
                         '-days', '1', '-extfile', str(directory / 'tls.ext'))
            der = directory / 'tls.der'
            self.openssl('x509', '-in', str(directory / 'tls.crt'), '-outform', 'DER', '-out', str(der))
            self.snapshot['services'][name] = {'generation': '1', 'certificates': [hashlib.sha256(der.read_bytes()).hexdigest()]}
            key = self.key(name, 'one')
            self.activate(name, 'one')
        # Explicit fixture call graph; no wildcard rules and no administrative credentials.
        graph = {'api-gateway': {'auth-service', 'project-service', 'issue-service', 'workflow-service'},
                 'issue-service': {'project-service', 'workflow-service'},
                 'project-service': {'issue-service', 'workflow-service'},
                 'workflow-service': {'project-service', 'issue-service'}}
        routes = json.loads((self.root / 'grpc-common-lib/src/main/resources/security/rpc-route-policies.json').read_text())['routes']
        for issuer, targets in graph.items():
            for route in routes:
                if route['audience'] in targets and route['tenantRequired']:
                    self.edge(issuer, route['audience'], route['rpc'], route['action'], ['WEB_TENANT', 'USER_PAT'], ['read', 'write'])
        self.edge('api-gateway', 'api-gateway', 'gatewayRequest', 'gatewayRequest', ['WEB_TENANT', 'USER_PAT'], ['read', 'write'])
        self.edge('auth-service', 'tenant-service', 'ru.taska.b2b.v1.TenantMembershipService/GetMembership',
                  'getMembership', ['SERVICE'], ['memberships:read'], 'membership')
        self.refresh()
        self.refresh_thread = threading.Thread(target=self.refresh_loop, daemon=True)
        self.refresh_thread.start()
        compose = json.loads((self.root / 'mcp-server/src/test/compose/compose.yml').read_text())
        template = copy.deepcopy(compose['services']['auth-service'])
        compose['services'] = {k: v for k, v in compose['services'].items() if k in ('database', 'kafka', 'storage')}
        self.password = secrets.token_hex(24)
        for name in self.modules:
            s = copy.deepcopy(template)
            env = s['environment']
            env.update({'DEPLOY_ENVIRONMENT': 'test', 'SPRING_APPLICATION_NAME': name,
                        'SPRING_R2DBC_URL': 'r2dbc:postgresql://database:5432/' + name.split('-')[0] + '_db',
                        'SPRING_LIQUIBASE_URL': 'jdbc:postgresql://database:5432/' + name.split('-')[0] + '_db',
                        'SPRING_CONFIG_ADDITIONAL_LOCATION': 'file:/run/identity/application.properties'})
            # api-gateway has no database; unused properties do not construct a database client.
            for key in ('JWT_SECRET', 'MCP_GATEWAY_SIGNING_SECRET'):
                env[key] = base64.b64encode(secrets.token_bytes(48)).decode()
            s['user'] = str(os.getuid()) + ':' + str(os.getgid())
            s['volumes'] = [str(self.dir / 'jars' / (name + '.jar')) + ':/app/app.jar:ro',
                            str(self.material / name) + ':/run/identity:ro', str(self.public) + ':/run/trust:ro']
            props = {'taska.rpc.context.enabled': 'true', 'taska.rpc.trust.snapshot': '/run/trust/trust.json',
                'taska.rpc.trust.signing-key': '/run/identity/active.signing.json',
                'taska.rpc.tls.ca-certificate': '/run/identity/ca.crt',
                'taska.rpc.tls.certificate': '/run/identity/tls.crt', 'taska.rpc.tls.private-key': '/run/identity/tls.key',
                'taska.rpc.trust.auth-host': 'auth-service', 'taska.rpc.trust.auth-port': '9090',
                'taska.rpc.trust.tenant-host': 'tenant-service', 'taska.rpc.trust.tenant-port': '9090',
                'spring.grpc.server.ssl.enabled': 'true', 'spring.grpc.server.ssl.secure': 'true',
                'spring.grpc.server.ssl.bundle': 'rpc', 'spring.grpc.server.ssl.client-auth': 'REQUIRE',
                'spring.ssl.bundle.pem.rpc.keystore.certificate': 'file:/run/identity/tls.crt',
                'spring.ssl.bundle.pem.rpc.keystore.private-key': 'file:/run/identity/tls.key',
                'spring.ssl.bundle.pem.rpc.truststore.certificate': 'file:/run/identity/ca.crt'}
            if name in ('project-service', 'issue-service', 'workflow-service'):
                env.update({'SPRING_R2DBC_USERNAME': 'rpc_' + name.split('-')[0], 'SPRING_R2DBC_PASSWORD': self.password})
            if name == 'tenant-service':
                env.update({'SPRING_R2DBC_USERNAME': 'taska_tenant_app', 'SPRING_R2DBC_PASSWORD': self.password,
                            'SPRING_LIQUIBASE_USER': 'taska_tenant_deployer', 'SPRING_LIQUIBASE_PASSWORD': self.password})
                for key in ('taska.tenant.membership-cursor-key', 'taska.tenant.platform-flags-cursor-key'):
                    props[key] = base64.b64encode(secrets.token_bytes(32)).decode()
            if name == 'auth-service':
                # Separate access-token key; RPC signing keys are never reused for end-user JWTs.
                self.command('java', str(self.root / 'scripts/harness/RpcFixtureKey.java'), 'generate',
                    str(self.material / name / 'jwt.pem'), str(self.material / name / 'private.der'),
                    str(self.material / name / 'public.der'))
                for kind in ('private', 'public'):
                    dest = self.material / name / (kind + '.der')
                    props['taska.b2b.sessions.ed25519-' + kind + '-key'] = base64.b64encode(dest.read_bytes()).decode()
                props.update({'taska.b2b.sessions.enabled': 'true', 'taska.b2b.sessions.ed25519-kid': 'owned-access',
                              'taska.b2b.sessions.refresh-receipt-key': base64.b64encode(secrets.token_bytes(32)).decode()})
            self.configure_service(name, env, props)
            path = self.material / name / 'application.properties'
            path.write_text(''.join(k + '=' + v + '\n' for k, v in props.items()))
            compose['services'][name] = s
        for name, s in compose['services'].items():
            s['labels'], s['networks'] = {e.LABEL: self.state['ownerNonce']}, ['default']
            s['volumes'] = [v.replace('./init-databases.sql', str(self.root / 'mcp-server/src/test/compose/init-databases.sql'))
                            for v in s.get('volumes', [])]
            if name in self.modules:
                s['image'] = 'eclipse-temurin:21-jre'
        compose['networks'] = {'default': {'labels': {e.LABEL: self.state['ownerNonce']}}}
        e.private_json(self.compose_file, compose)
        self.store.save(self.state)
        self.compose('up', '-d', '--wait', '--wait-timeout', '120', 'database', 'kafka', 'storage', timeout=180)
        self.sql('postgres', 'CREATE DATABASE tenant_db;')
        for service in ('project', 'issue', 'workflow'):
            self.sql(service + '_db', "CREATE SCHEMA taska;CREATE ROLE rpc_%s LOGIN NOINHERIT PASSWORD '%s';ALTER ROLE rpc_%s SET app.rpc_scope_enforced='true';GRANT USAGE ON SCHEMA taska TO rpc_%s;ALTER DEFAULT PRIVILEGES IN SCHEMA taska GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO rpc_%s;" % (service, self.password, service, service, service))
        self.compose('cp', str(self.root / 'common-lib/src/main/resources/db/tenant/bootstrap.sql'), 'database:/tmp/bootstrap.sql')
        self.compose('cp', str(self.root / 'common-lib/src/main/resources/db/tenant/bootstrap.psql'), 'database:/tmp/bootstrap.psql')
        self.compose('exec', '-T', 'database', 'psql', '-U', 'taska', '-d', 'tenant_db', '-v', 'service=tenant', '-v', 'schema=taska', '-f', '/tmp/bootstrap.psql')
        self.sql('tenant_db', "CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator;ALTER ROLE taska_tenant_app PASSWORD '%s';ALTER ROLE taska_tenant_deployer PASSWORD '%s';" % (self.password, self.password))
        self.prepare_extra_databases()
        self.compose('up', '-d', *self.modules, timeout=180)
        for name in self.modules:
            endpoint = 'http://' + self.compose('port', name, '8080').strip()
            self.wait_ready(endpoint)
            if name == 'api-gateway':
                self.state['origin'] = endpoint
        self.store.save(self.state)

    def sql(self, database, sql):
        return self.compose('exec', '-T', 'database', 'psql', '-X', '-qAt', '-U', 'taska', '-d', database,
                            '-v', 'ON_ERROR_STOP=1', input_text=sql)

    def wait_ready(self, endpoint):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if self.compose('ps', '-a', '--status', 'exited', '-q').strip():
                raise e.Blocked('A packaged service failed during startup; see private diagnostic')
            try:
                if e.Http(endpoint).request('GET', '/actuator/health/readiness').status == 200:
                    return
            except e.Blocked:
                pass
            time.sleep(1)
        raise e.Blocked('Local service readiness deadline exceeded')

    def seed(self):
        actor, web = str(uuid.uuid4()), str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','owned@harness.invalid','Owned','ACTIVE','USER');INSERT INTO taska.web_sessions(id,user_id,state,assurance,auth_version,created_at,expires_at) VALUES('%s','%s','ACTIVE','PASSWORD',1,now(),now()+interval '1 hour');" % (actor, web, actor))
        clients = []
        for _ in range(2):
            tenant, session = str(uuid.uuid4()), str(uuid.uuid4())
            self.sql('tenant_db', "BEGIN;INSERT INTO taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id) VALUES('%s','Owned','%s','ACTIVE','FREE','%s','%s',decode('00','hex'),'%s');INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');COMMIT;" % (tenant, actor, str(uuid.uuid4()), actor, str(uuid.uuid4()), tenant, actor))
            self.sql('auth_db', "INSERT INTO taska.tenant_sessions(id,web_session_id,user_id,tenant_id,membership_version,created_at,expires_at) VALUES('%s','%s','%s','%s',1,now(),now()+interval '1 hour');" % (session, web, actor, tenant))
            claims = {'sub': actor, 'iss': 'taska-auth', 'aud': ['taska-api'], 'iat': int(time.time()), 'exp': int(time.time()) + 600,
                      'sid': session, 'tenant_id': tenant, 'kind': 'WEB_TENANT', 'account_generation': '1',
                      'membership_generation': '1', 'scopes': ['read', 'write']}
            b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=')
            data = b64(e.canonical({'alg': 'EdDSA', 'kid': 'owned-access', 'typ': 'JWT'})) + b'.' + b64(e.canonical(claims))
            source, signature = self.material / 'jwt-input', self.material / 'jwt-signature'
            source.write_bytes(data)
            self.command('java', str(self.root / 'scripts/harness/RpcFixtureKey.java'), 'sign',
                         str(self.material / 'auth-service/jwt.pem'), str(source), str(signature))
            token = (data + b'.' + b64(signature.read_bytes())).decode()
            clients.append((tenant, e.Http(self.state['origin'], token)))
        return actor, clients

    def counts(self):
        return tuple(self.sql(db + '_db', 'SELECT count(*) FROM taska.' + table).strip()
                     for db, table in (('project', 'projects'), ('project', 'outbox_events'), ('issue', 'issues'), ('issue', 'outbox_events')))

    def kafka_created_events(self, topic, event_type):
        # A topic also contains stats/other events and legitimate delivery retries. Consume to
        # quiescence, then compare the immutable creation event IDs rather than the first N rows.
        argv = ['docker', 'compose', '--env-file', '/dev/null', '-p', self.project,
                '-f', str(self.compose_file), 'exec', '-T', 'kafka',
                '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'kafka:9092',
                '--topic', topic, '--from-beginning', '--timeout-ms', '5000']
        result = subprocess.run(argv, cwd=self.root, capture_output=True, text=True, timeout=25)
        e.require(result.returncode in (0, 1), 'Owned Kafka consumer failed')
        return [record for line in result.stdout.splitlines() if line.startswith('{')
                for record in [json.loads(line)] if record.get('eventType') == event_type]

    def scenarios(self):
        actor, clients = self.seed()
        # Actuator readiness does not establish lazy business/control mTLS channels.
        # Check authenticated read paths before measuring writes; retain normal deadlines.
        readiness = []
        initial = self.counts()
        for _, client in clients:
            for resource in ('projects', 'issues'):
                readiness.append(wait_protected_read(client, '/api/v1/' + resource + '/' + str(uuid.uuid4()), 404))
        e.require(self.counts() == initial, 'Protected read readiness changed domain/outbox state')
        projects = []
        for tenant, client in clients:
            project = client.expect('POST', '/api/v1/projects', 201, {'projectKey': 'OWNED', 'name': 'Owned'}).json()
            projects.append(project['id'])
            issue = client.expect('POST', '/api/v1/projects/' + project['id'] + '/issues', 201,
                {'issueType': 'TASK', 'summary': 'Owned', 'description': '', 'priority': 'MEDIUM'},
                {'Idempotency-Key': 'owned-same-business-key'}).json()
            e.require(issue['issueKey'] == 'OWNED-1', 'Tenant-local issue sequence differs')
            e.require(self.sql('project_db', "SELECT tenant_id FROM taska.projects WHERE id='%s'" % project['id']).strip() == tenant, 'Project tenant provenance differs')
            e.require(self.sql('issue_db', "SELECT tenant_id FROM taska.issues WHERE id='%s'" % issue['id']).strip() == tenant, 'Issue tenant provenance differs')
        for topic in ('project.events', 'issue.events'):
            records = self.kafka_created_events(topic, 'ProjectCreated' if topic == 'project.events' else 'IssueCreated')
            e.require(len(records) >= 2, 'Kafka did not deliver both owned creation events')
            e.require({record['tenantId'] for record in records} == {t for t, _ in clients}, 'Kafka envelope lost verified tenant')
            e.require(len({record['id'] for record in records}) == 2, 'Kafka event identity is not unique')
        before = self.counts()
        clients[0][1].expect('GET', '/api/v1/projects/' + projects[1], 404)
        clients[0][1].expect('POST', '/api/v1/projects', 403, {'projectKey': 'WRONG', 'name': 'Owned'}, {'X-Tenant-Id': clients[1][0]})
        # Rotate the gateway key while domain replicas keep their existing channels/verifiers.
        with self.lock:
            next_key = self.key('api-gateway', 'two')
        self.refresh()
        self.confirm_delivery()
        self.activate('api-gateway', 'two')
        clients[0][1].expect('GET', '/api/v1/projects/' + projects[0], 200)
        with self.lock:
            for key in self.snapshot['keys']:
                if key['issuer'] == 'api-gateway':
                    key['revoked'] = True
        self.refresh()
        response = clients[0][1].request('POST', '/api/v1/projects', {'projectKey': 'REVOKED', 'name': 'Owned'})
        e.require(response.status in (401, 503), 'Revoked signing key admitted a write')
        e.require(self.counts() == before, 'Rejected requests changed domain/outbox state')
        # Restore the current key, then revoke primary membership without waiting for a cache TTL.
        with self.lock:
            self.snapshot['keys'][-1]['revoked'] = False
        self.refresh()
        tenant, client = clients[0]
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s'" % (tenant, actor))
        e.require(client.request('POST', '/api/v1/projects', {'projectKey': 'SUSPENDED', 'name': 'Owned'}).status in (401, 403), 'Suspended member admitted')
        e.require(self.counts() == before, 'Membership denial changed domain/outbox state')
        # Required trust loss closes admission while existing processes/connections remain alive.
        self.stop.set()
        self.refresh_thread.join(5)
        (self.public / 'trust.json').unlink()
        clients[1][1].expect('POST', '/api/v1/projects', 503, {'projectKey': 'OUTAGE', 'name': 'Owned'})
        e.require(self.counts() == before, 'Trust outage changed domain/outbox state')
        e.require(not self.refresh_error, 'Trust refresher failed during run')
        return {'restAuthNestedRpcRlsOutboxKafka': 'PASS', 'tenants': 2, 'rotationRevokeOutage': 'PASS',
                'membershipRevocation': 'PASS', 'statePreservation': 'PASS',
                'protectedReadinessAttempts': readiness}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cleanup', help='Signed state.json from an interrupted owned run')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    store = e.StateStore(root)
    if args.cleanup:
        state = store.load(Path(args.cleanup))
        with e.mutex(store.path(state['runId']).parent / '.run-lock'):
            local = e.Local(root, store, state)
            local.cleanup()
            shutil.rmtree(local.dir / 'material', ignore_errors=True)
        print(json.dumps({'cleanup': 'PASS'}))
        return
    state = store.create(secrets.token_hex(8), 'local', 'isolated-trusted-rpc', '')
    local = TrustedLocal(root, store, state)
    report = {'source': e.source(root), 'scenario': 'trusted-rpc', 'status': 'FAIL'}
    print(json.dumps({'runState': str(store.path(state['runId'])), 'status': 'RUNNING'}), flush=True)
    with e.mutex(local.dir / '.run-lock'):
        try:
            local.prepare()
            report.update(local.scenarios())
            report.update(status='PASS', jars=state['jarRevisions'])
        except Exception as exc:
            report['error'] = type(exc).__name__ + ': ' + str(exc)
            report['trustRefresherFailed'] = bool(local.refresh_error)
            if (local.public / 'trust.json').exists():
                metadata = json.loads((local.public / 'trust.json').read_text())
                report['trustMetadata'] = {k: metadata[k] for k in ('generation', 'issuedAt', 'validUntil')}
                e.private_json(local.dir / 'trust-public-diagnostic.json', metadata)
            if local.compose_file.exists():
                try:
                    e.private_json(local.dir / 'startup-diagnostic.json', {'log': local.compose('logs', '--no-color', '--tail', '120')})
                except e.Failure:
                    report['diagnostic'] = 'unavailable'
            raise
        finally:
            local.stop.set()
            local.cleanup()
            shutil.rmtree(local.material, ignore_errors=True)
            report['cleanup'] = state['cleanupStatus']
            e.private_json(local.dir / 'trusted-rpc-report.json', report)
            print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
