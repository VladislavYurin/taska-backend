"""Isolated backend E2E lifecycle; stdlib only. Never execute commands from run-state."""
import base64
import contextlib
from dataclasses import dataclass
from datetime import datetime, timezone
import fcntl
import hashlib
from http.client import HTTPException
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import stat
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler
import uuid

MODULES = ('auth-service', 'project-service', 'workflow-service', 'issue-service',
           'notification-service', 'api-gateway', 'mcp-server')
LABEL = 'dev.taska.harness.owner'
RUN_PATTERN = r'[0-9a-f]{16}'
ASSERTIONS = 0


class Invalid(ValueError):
    pass


class Blocked(RuntimeError):
    pass


class Failure(RuntimeError):
    pass


class UnknownWrite(Failure):
    """The server may have committed a write. Do not retry blindly."""


def now():
    return datetime.now(timezone.utc).isoformat()


def require(value, message):
    global ASSERTIONS
    ASSERTIONS += 1
    if not value:
        raise Failure(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def private_json(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + '.' + secrets.token_hex(6))
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(value, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


def secure_file(path):
    path = Path(path)
    s = path.lstat()
    if (not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid() or s.st_mode & 0o077):
        raise Invalid('File must be owned by current user, regular, mode 0600')
    return path


@contextlib.contextmanager
def mutex(path, wait=False):
    fd = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | (0 if wait else fcntl.LOCK_NB))
        except BlockingIOError as exc:
            raise Invalid('Run is active; concurrent cleanup/reuse refused') from exc
        yield
    finally:
        os.close(fd)


class StateStore:
    """HMAC binds registry/environment to this checkout. The key is never in state/report."""
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.directory = self.root / 'target/harness-e2e'
        if self.directory.is_symlink() or (self.root / 'target').is_symlink():
            raise Invalid('Artifact directory cannot be a symlink')
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.directory.stat().st_uid != os.getuid() or self.directory.stat().st_mode & 0o077:
            raise Invalid('Artifact directory must be private (0700)')
        self.keyfile = self.directory / '.state-key'
        # Publish a complete key atomically. Parallel first runs never observe an empty file.
        tmp_key = self.directory / ('.key-' + secrets.token_hex(8))
        fd = os.open(tmp_key, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            with os.fdopen(fd, 'wb') as f:
                f.write(secrets.token_bytes(32))
                f.flush()
                os.fsync(f.fileno())
            try:
                os.link(tmp_key, self.keyfile)
            except FileExistsError:
                pass
        finally:
            tmp_key.unlink()
        self.key = secure_file(self.keyfile).read_bytes()
        if len(self.key) != 32:
            raise Invalid('Invalid state key')

    def path(self, run_id):
        if not re.fullmatch(RUN_PATTERN, run_id):
            raise Invalid('run-id must be 16 lowercase hex characters')
        return self.directory / run_id / 'state.json'

    def create(self, run_id, tier, environment, actor):
        path = self.path(run_id)
        path.parent.mkdir(mode=0o700)  # refuse reuse, including parallel duplicate RUN
        state = {'schemaVersion': 1, 'runId': run_id, 'checkout': str(self.root),
                 'tier': tier, 'environment': environment, 'actor': actor,
                 'ownerNonce': secrets.token_hex(32), 'resources': [],
                 'pendingWrites': [], 'cleanupStatus': 'PENDING', 'startedAt': now()}
        self.save(state)
        return state

    def save(self, state):
        path = self.path(state['runId'])
        if path.parent.is_symlink() or state.get('checkout') != str(self.root):
            raise Invalid('State checkout/path mismatch')
        private_json(path, {'state': state, 'mac': hmac.new(self.key, canonical(state), hashlib.sha256).hexdigest()})

    def load(self, path):
        path = Path(path).absolute()
        expected = self.path(path.parent.name)
        if path != expected or path.parent.is_symlink():
            raise Invalid('State must belong to this checkout run directory')
        try:
            envelope = json.loads(secure_file(path).read_text())
            state, mac = envelope['state'], envelope['mac']
            if not hmac.compare_digest(mac, hmac.new(self.key, canonical(state), hashlib.sha256).hexdigest()):
                raise Invalid('State authentication failed')
            if (state['schemaVersion'] != 1 or state['checkout'] != str(self.root)
                    or state['runId'] != path.parent.name or state['tier'] not in ('local', 'stage')):
                raise Invalid('State identity mismatch')
            return state
        except (KeyError, TypeError, json.JSONDecodeError) as exc:
            raise Invalid('Invalid state schema') from exc


def validate_resource(resource, run_id, actor):
    if (resource.get('type') != 'project' or resource.get('runId') != run_id
            or resource.get('actor') != actor or resource.get('origin') != 'created-response'):
        raise Invalid('Resource ownership/provenance mismatch')
    try:
        uuid.UUID(resource['id'])
    except (ValueError, KeyError, TypeError) as exc:
        raise Invalid('Invalid resource ID') from exc
    if resource.get('projectKey') != 'H' + run_id[:14].upper() + resource.get('slot', ''):
        raise Invalid('Resource project identity mismatch')


@dataclass
class Response:
    status: int
    headers: dict
    body: bytes

    def header(self, name):
        return next((v for k, v in self.headers.items() if k.lower() == name.lower()), None)

    def json(self):
        return json.loads(self.body) if self.body.strip() else None


@dataclass(frozen=True)
class OAuthIds:
    app_id: str
    client_id: str

    def __post_init__(self):
        try:
            uuid.UUID(self.app_id)
        except (ValueError, TypeError, AttributeError) as exc:
            raise Invalid('Internal app ID must be a UUID, not public client_id') from exc
        if not isinstance(self.client_id, str) or not self.client_id.strip():
            raise Invalid('Public client_id must be nonempty')


def business_projection(value):
    """Ignore only known ephemeral transport fields, not every URL/business field."""
    if isinstance(value, dict):
        return {k: business_projection(v) for k, v in value.items()
                if k not in ('avatarUrl', 'downloadUrl', 'uploadUrl', 'presignedUrl')}
    if isinstance(value, list):
        return [business_projection(v) for v in value]
    return value


def capability_result(required, available):
    missing = sorted(set(required) - {k for k, v in available.items() if v is True})
    return {'status': 'BLOCKED' if missing else 'PASS',
            'reason': 'Missing capabilities: ' + ', '.join(missing) if missing else ''}


def revision_result(before, after):
    if not before or not after or any(not v for v in before.values()) or set(before) != set(after):
        return {'status': 'BLOCKED', 'reason': 'Service revision evidence unavailable'}
    if before != after:
        return {'status': 'PARTIAL', 'reason': 'Service revisions changed during run'}
    return {'status': 'PASS', 'reason': ''}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Http:
    def __init__(self, origin, token=None):
        self.origin, self.token = origin, token
        self.operations = set()
        self.request_ids = []
        self.opener = build_opener(NoRedirect())

    def request(self, method, path, body=None, headers=None):
        if not path.startswith('/') or path.startswith('//') or '#' in path:
            raise Invalid('Request path must be relative to configured origin')
        self.operations.add(method + ' ' + re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27,}', '{id}', path))
        hdr = {'Content-Type': 'application/json', 'X-Request-Id': str(uuid.uuid4())}
        if self.token:
            hdr['Authorization'] = 'Bearer ' + self.token
        hdr.update(headers or {})
        self.request_ids.append(hdr['X-Request-Id'])
        req = Request(self.origin + path, data=None if body is None else canonical(body), headers=hdr, method=method)
        try:
            try:
                response = self.opener.open(req, timeout=10)
            except HTTPError as exc:
                response = exc
            with response:
                return Response(response.status, dict(response.headers), response.read(4 * 1024 * 1024))
        except (URLError, TimeoutError, socket.timeout, ConnectionError, HTTPException) as exc:
            if method not in ('GET', 'HEAD', 'OPTIONS'):
                raise UnknownWrite('Write outcome unknown; reconcile own registry before retry') from exc
            raise Blocked('HTTP transport unavailable') from exc

    def expect(self, method, path, status, body=None, headers=None):
        response = self.request(method, path, body, headers)
        require(response.status == status, 'Unexpected HTTP status for ' + method + ' ' + path.split('?')[0]
                + ': expected %s, got %s' % (status, response.status))
        return response


def run_command(argv, cwd, timeout=600, input_text=None):
    # A process group bounds Maven/Docker descendants, including interrupted runs.
    try:
        child = subprocess.Popen(argv, cwd=cwd, stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
    except FileNotFoundError as exc:
        raise Blocked('Required executable unavailable') from exc
    try:
        output, _ = child.communicate(input_text, timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        termination_error = False
        try:
            os.killpg(child.pid, signal.SIGTERM)
            time.sleep(0.1)
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError:
            termination_error = True
            if child.poll() is None:
                child.kill()
        child.communicate()
        if termination_error:
            raise Failure('Process group termination could not be confirmed') from None
        if isinstance(__import__('sys').exc_info()[1], KeyboardInterrupt):
            raise
        raise Failure('Local command timed out') from None
    if child.returncode:
        raise Failure('Local command failed (%s)' % child.returncode)
    return output


def git(root, *args):
    return run_command(['git', *args], root).strip()


def source(root):
    return {'sourceHead': git(root, 'rev-parse', 'HEAD'), 'sourceTree': git(root, 'rev-parse', 'HEAD^{tree}'),
            'dirty': bool(git(root, 'status', '--porcelain'))}


def load_config(path):
    if not path:
        raise Invalid('stage requires --config with approved environment and actor allowlist')
    c = json.loads(secure_file(Path(path)).read_text())
    origin = c.get('origin', '')
    parsed = urlsplit(origin)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.path or parsed.query or parsed.fragment or origin not in c.get('allowedOrigins', [])):
        raise Invalid('Stage origin must be an exact allowlisted HTTPS origin')
    if not c.get('environmentId') or c.get('allowProjectArchiveResidue') is not True:
        raise Invalid('Explicit environment identity and project archive residue policy required')
    actors = c.get('actors', {})
    for role in ('A', 'M', 'V', 'O', 'G'):
        a = actors.get(role, {})
        try:
            uuid.UUID(a['id'])
        except (KeyError, ValueError, TypeError) as exc:
            raise Invalid('Dedicated A/M/V/O/G actor IDs required') from exc
        if not re.fullmatch(r'[A-Z][A-Z0-9_]*', a.get('tokenEnv', '')):
            raise Invalid('Actor tokenEnv must name an environment variable')
    if len({a['id'] for a in actors.values()}) != len(actors):
        raise Invalid('Actors must be distinct')
    if not set(MODULES[:-1]) <= set(c.get('revisionPaths', {})):
        raise Invalid('revisionPaths required; static declared revisions are not observations')
    for path in c['revisionPaths'].values():
        if not re.fullmatch(r'/[A-Za-z0-9_./-]+', path):
            raise Invalid('Revisions must use same-origin read paths')
    return c


class Local:
    def __init__(self, root, store, state):
        self.root, self.store, self.state = Path(root), store, state
        self.dir = store.path(state['runId']).parent
        self.project = 'taska-harness-' + state['runId']
        self.compose_file = self.dir / 'compose.json'

    def compose(self, *args, timeout=600, input_text=None):
        return run_command(['docker', 'compose', '--env-file', '/dev/null', '-p', self.project,
                            '-f', str(self.compose_file), *args], self.root, timeout, input_text)

    def provision(self):
        try:
            run_command(['docker', 'info', '--format', '{{.ServerVersion}}'], self.root, 15)
        except Failure as exc:
            raise Blocked('Docker daemon unavailable') from exc
        self.state['dockerDaemon'] = run_command(['docker', 'info', '--format', '{{.ID}}'], self.root, 15).strip()
        if not self.state['dockerDaemon']:
            raise Blocked('Docker daemon identity unavailable')
        self.store.save(self.state)
        with mutex(self.store.directory / '.build-lock', wait=True):
            run_command(['./mvnw', '-B', '-ntp', '-pl', ','.join(MODULES), '-am', 'package', '-DskipTests'], self.root, 1800)
            jars = self.dir / 'jars'
            jars.mkdir()
            revisions = {}
            for module in MODULES:
                src = self.root / module / 'target' / (module + '-1.0-SNAPSHOT.jar')
                shutil.copyfile(src, jars / (module + '.jar'))
                revisions[module] = hashlib.sha256(src.read_bytes()).hexdigest()
        compose = json.loads((self.root / 'mcp-server/src/test/compose/compose.yml').read_text())
        jwt, signing = (base64.b64encode(secrets.token_bytes(48)).decode() for _ in range(2))
        for name, service in compose['services'].items():
            service['labels'] = {LABEL: self.state['ownerNonce']}
            service['networks'] = ['default']
            env = service.get('environment', {})
            for key, val in env.items():
                if val.startswith('${TASKA_E2E_JWT_SECRET'):
                    env[key] = jwt
                if val.startswith('${TASKA_E2E_SIGNING_SECRET'):
                    env[key] = signing
            service['volumes'] = [v.replace('${TASKA_E2E_JAR_DIR:?set by test}', str(jars))
                                  .replace('./init-databases.sql', str(self.root / 'mcp-server/src/test/compose/init-databases.sql'))
                                  for v in service.get('volumes', [])]
            if name in MODULES:
                service['image'] = 'eclipse-temurin:21-jre'
        compose['networks'] = {'default': {'labels': {LABEL: self.state['ownerNonce']}}}
        private_json(self.compose_file, compose)
        self.state['composeProject'] = self.project
        self.state['jarRevisions'] = revisions
        self.store.save(self.state)
        try:
            self.compose('up', '-d', '--wait', '--wait-timeout', '180', timeout=300)
        except Failure:
            private_json(self.dir / 'startup-diagnostic.json', {'log': self.compose('logs', '--no-color', '--tail', '100')})
            raise Failure('Compose startup failed; private startup-diagnostic.json retained')
        for module in MODULES:
            endpoint = 'http://' + self.compose('port', module, '8080').strip()
            self.wait_ready(endpoint)
            if module == 'api-gateway':
                self.state['origin'] = endpoint
        self.store.save(self.state)
        actors = {}
        rows = []
        for index, role in enumerate(('A', 'M', 'V', 'O', 'G')):
            actor = str(uuid.uuid4())
            actors[role] = {'id': actor}
            rows.append("('%s','%s@harness.invalid','Harness %s','ACTIVE','%s')" %
                        (actor, role.lower(), role, 'GLOBAL_ADMIN' if role == 'G' else 'USER'))
        password = secrets.token_hex(20)  # fixture-only; SQL alphabet constrained
        sql = "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES " + ','.join(rows) + ";"
        sql += "INSERT INTO taska.credentials(user_id,credential_type,secret_hash,algo) SELECT id,'PASSWORD',crypt('%s',gen_salt('bf')),'BCRYPT' FROM taska.users WHERE email LIKE '%%@harness.invalid';" % password
        self.compose('exec', '-T', 'database', 'psql', '-U', 'taska', '-d', 'auth_db', '-v', 'ON_ERROR_STOP=1', input_text=sql)
        for role, a in actors.items():
            a['token'] = Http(self.state['origin']).expect('POST', '/api/v1/auth/login', 200,
                        {'email': role.lower() + '@harness.invalid', 'password': password}).json()['accessToken']
        self.state['actor'] = actors['A']['id']
        self.state['actors'] = {k: v['id'] for k, v in actors.items()}
        self.store.save(self.state)
        return actors

    def wait_ready(self, endpoint):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                if Http(endpoint).request('GET', '/actuator/health/readiness').status == 200:
                    return
            except Blocked:
                pass
            time.sleep(1)
        raise Blocked('Local service readiness deadline exceeded')

    def verify_daemon(self):
        expected = self.state.get('dockerDaemon')
        if self.state.get('composeProject') and not expected:
            raise Invalid('Docker daemon identity missing from provisioned state')
        if expected and run_command(['docker', 'info', '--format', '{{.ID}}'], self.root, 15).strip() != expected:
            raise Invalid('Docker daemon identity differs from run-state')

    def revisions(self):
        self.verify_daemon()
        # Observe immutable container image IDs and mounted jar bytes; restart/replacement changes IDs.
        result = {}
        ids = run_command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project], self.root).split()
        if not ids:
            return {}
        for container in json.loads(run_command(['docker', 'inspect', *ids], self.root)):
            labels = container['Config']['Labels']
            if labels.get(LABEL) != self.state['ownerNonce']:
                raise Invalid('Foreign container in run project')
            if not container['State']['Running']:
                raise Blocked('Local service stopped during run')
            module = labels['com.docker.compose.service']
            digest = self.state.get('jarRevisions', {}).get(module, '')
            jar = self.dir / 'jars' / (module + '.jar')
            if jar.exists():
                digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            result[module] = container['Id'] + ':' + container['Image'] + ':' + digest
        if not set(MODULES) <= set(result):
            raise Blocked('Not all expected local service revisions are observable')
        return result

    def cleanup(self):
        self.verify_daemon()
        # Do not run compose from mutable state. Validate every object before the first deletion.
        containers = run_command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project], self.root).split()
        networks = run_command(['docker', 'network', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + self.project], self.root).split()
        for kind, ids in (('container', containers), ('network', networks)):
            if ids:
                objects = json.loads(run_command(['docker', kind, 'inspect', *ids], self.root))
                for obj in objects:
                    labels = obj['Config']['Labels'] if kind == 'container' else obj['Labels']
                    if (labels.get(LABEL) != self.state['ownerNonce']
                            or labels.get('com.docker.compose.project') != self.project):
                        raise Invalid('Cleanup rejected foreign Docker ownership')
        if containers:
            run_command(['docker', 'rm', '-f', '-v', *containers], self.root, 120)
        if networks:
            run_command(['docker', 'network', 'rm', *networks], self.root, 60)
        self.state['cleanupStatus'] = 'PASS'
        self.state['resources'] = []
        self.state['pendingWrites'] = []
        self.store.save(self.state)
        self.compose_file.unlink(missing_ok=True)  # contains disposable fixture secrets
        if (self.dir / 'jars').is_dir():
            shutil.rmtree(self.dir / 'jars')


def stage_revisions(config):
    result = {}
    http = Http(config['origin'])
    for service, path in config['revisionPaths'].items():
        response = http.expect('GET', path, 200).json()
        # Endpoint contract: {"revision":"immutable build SHA/digest"}.
        revision = response.get('revision') if isinstance(response, dict) else None
        if not isinstance(revision, str) or not re.fullmatch(r'[A-Za-z0-9_.:@+-]{7,200}', revision):
            raise Blocked('Observed service revision unavailable')
        result[service] = revision
    return result


def project_key(run_id, slot):
    return 'H' + run_id[:14].upper() + slot


def register_project(store, state, actor, slot, project):
    resource = {'type': 'project', 'id': project['id'], 'projectKey': project_key(state['runId'], slot),
                'slot': slot, 'actor': actor, 'runId': state['runId'], 'origin': 'created-response'}
    validate_resource(resource, state['runId'], actor)
    require(project.get('createdBy') == actor and project.get('projectKey') == resource['projectKey'],
            'Created project ownership differs from requested actor/key')
    if not any(r['id'] == resource['id'] for r in state['resources']):
        state['resources'].append(resource)
    state['pendingWrites'] = [p for p in state['pendingWrites'] if p['slot'] != slot]
    store.save(state)
    return resource


def create_project(store, state, client, actor, slot):
    key = project_key(state['runId'], slot)
    # Write-ahead intent survives timeout/kill before receiving a resource ID.
    state['pendingWrites'].append({'slot': slot, 'actor': actor, 'projectKey': key})
    store.save(state)
    project = client.expect('POST', '/api/v1/projects', 201,
                            {'projectKey': key, 'name': 'Harness ' + state['runId'],
                             'description': 'Isolated E2E fixture', 'color': '#0052CC'}).json()
    return register_project(store, state, actor, slot, project)


def reconcile_projects(store, state, clients):
    for pending in list(state['pendingWrites']):
        client = clients[pending['actor']]
        matches = [p for p in client.expect('GET', '/api/v1/projects', 200).json()['items']
                   if p.get('projectKey') == pending['projectKey'] and p.get('createdBy') == pending['actor']]
        if len(matches) == 1:
            register_project(store, state, pending['actor'], pending['slot'], matches[0])
        else:
            # A timeout may still commit later; absence is not proof of no write.
            raise Failure('Write outcome remains unknown; pending intent retained for retry cleanup')


def cleanup_projects(store, state, clients):
    reconcile_projects(store, state, clients)
    for resource in reversed(state['resources']):
        validate_resource(resource, state['runId'], resource['actor'])
        if resource['actor'] not in state['actors'].values():
            raise Invalid('Resource actor outside approved run')
        client = clients[resource['actor']]
        path = '/api/v1/projects/' + resource['id']
        actual = client.expect('GET', path, 200).json()
        if (actual.get('createdBy') != resource['actor'] or actual.get('projectKey') != resource['projectKey']
                or actual.get('currentUserRole') != 'ADMIN'):
            raise Invalid('Live project ownership mismatch; cleanup refused')
        # Remove only members added in this own project before archival.
        if not actual.get('archivedAt'):
            members = client.expect('GET', path + '/members', 200).json()['members']
            for member in members:
                if member['userId'] != resource['actor']:
                    if member['userId'] not in state['actors'].values():
                        raise Invalid('Unexpected member in fixture project')
                    client.expect('DELETE', path + '/members/' + member['userId'], 204)
        archived = client.expect('DELETE', path, 200).json()
        require(bool(archived.get('archivedAt')), 'Cleanup did not archive project')
        resource['archived'] = True
        store.save(state)
    state['cleanupStatus'] = 'PASS'
    store.save(state)


def scenario_01(client):
    initial_assertions = ASSERTIONS
    public = Http(client.origin)
    doc = public.expect('GET', '/v3/api-docs', 200).json()
    require(bool(doc.get('paths')) and doc.get('openapi', '').startswith('3.'), 'Published contract missing')
    require(bool(public.expect('GET', '/openapi.yml', 200).body), 'YAML contract missing')
    public.expect('GET', '/api/v1/meta', 401)
    meta = client.expect('GET', '/api/v1/meta', 200)
    require(bool(meta.json()), 'Empty metadata')
    etag = meta.header('etag')
    require(bool(etag), 'Metadata ETag missing')
    cached = client.expect('GET', '/api/v1/meta', 304, headers={'If-None-Match': etag})
    require(not cached.body, '304 response contains a body')
    client.expect('GET', '/api/v1/meta', 200, headers={'If-None-Match': '"stale-harness"'})
    methods = public.expect('GET', '/api/v1/auth/methods', 200)
    require('no-store' in (methods.header('cache-control') or ''), 'Auth methods must not be cached')
    return {'status': 'PARTIAL', 'reason': 'YAML semantic parity and unknown downstream-enum fault fixture remain manual',
            'testCount': ASSERTIONS - initial_assertions, 'assertionScope': ['published-contract', 'meta-auth', 'meta-etag', 'auth-methods-cache'],
            'operations': sorted(client.operations | public.operations)}


def scenario_08(store, state, clients, actors):
    initial_assertions = ASSERTIONS
    a, m, v, o, g = (clients[actors[r]['id']] for r in ('A', 'M', 'V', 'O', 'G'))
    p = create_project(store, state, a, actors['A']['id'], 'A')
    q = create_project(store, state, o, actors['O']['id'], 'O')
    path = '/api/v1/projects/' + p['id']
    original = a.expect('GET', path, 200).json()
    require(original['currentUserRole'] == 'ADMIN', 'Creator is not project ADMIN')
    a.expect('POST', '/api/v1/projects', 409, {'projectKey': p['projectKey'], 'name': 'duplicate'})
    for invalid in ({'projectKey': 'bad key', 'name': 'invalid'},
                    {'projectKey': p['projectKey'] + 'X', 'name': ''},
                    {'projectKey': p['projectKey'] + 'X', 'name': 'invalid', 'color': 'red'}):
        a.expect('POST', '/api/v1/projects', 400, invalid)
    require(business_projection(a.expect('GET', path, 200).json()) == business_projection(original),
            'Rejected create modified existing project')
    for role, project_role in (('M', 'MEMBER'), ('V', 'VIEWER')):
        a.expect('POST', path + '/members', 201, {'userId': actors[role]['id'], 'role': project_role})
    members = a.expect('GET', path + '/members', 200).json()['members']
    require({x['userId']: x['role'] for x in members} ==
            {actors['A']['id']: 'ADMIN', actors['M']['id']: 'MEMBER', actors['V']['id']: 'VIEWER'},
            'Unexpected project roles')
    for client in (m, v, o, g):
        client.expect('PATCH', path, 403, {'name': 'forbidden'})
    for client in (o, g):
        client.expect('GET', path, 403)
        client.expect('GET', path + '/context', 403)
        client.expect('GET', path + '/members', 403)
    require(a.expect('GET', path, 200).json()['name'] == original['name'], 'Forbidden mutation changed project')
    changed = a.expect('PATCH', path, 200, {'name': 'Harness renamed'}).json()
    require(changed['description'] == original['description'] and changed['color'] == original['color']
            and changed['projectKey'] == original['projectKey'], 'PATCH lost omitted fields')
    cleared = a.expect('PATCH', path, 200, {'description': None, 'color': None}).json()
    require(cleared.get('description') is None and cleared.get('color') is None, 'PATCH null did not clear fields')
    a.expect('PATCH', path, 400, {'name': None})
    require(a.expect('GET', path, 200).json()['name'] == 'Harness renamed', 'Invalid PATCH changed name')
    for target_role in ('VIEWER', 'MEMBER'):
        a.expect('PATCH', path + '/members/' + actors['M']['id'], 200, {'role': target_role})
        require(m.expect('GET', path, 200).json()['currentUserRole'] == target_role, 'Role did not take effect')
    # Backend defines the last-admin guard as FAILED_PRECONDITION (400).
    a.expect('DELETE', path + '/members/' + actors['A']['id'], 400)
    a.expect('PATCH', path + '/members/' + actors['A']['id'], 400, {'role': 'VIEWER'})
    require(a.expect('GET', path, 200).json()['currentUserRole'] == 'ADMIN', 'Last ADMIN guard lost ownership')
    a.expect('DELETE', path + '/members/' + actors['M']['id'], 204)
    m.expect('GET', path, 403)
    own = a.expect('GET', '/api/v1/projects', 200).json()['items']
    require(p['id'] in {x['id'] for x in own} and q['id'] not in {x['id'] for x in own}, 'Project list leaks outsider project')
    a.expect('GET', '/api/v1/project-stats', 200)
    a.expect('GET', path + '/context', 200)
    a.expect('GET', '/api/v1/projects/not-a-uuid', 400)
    return {'status': 'PARTIAL', 'reason': 'Inactive signup/BLOCKED membership fixtures and cross-project issue/label isolation remain manual',
            'testCount': ASSERTIONS - initial_assertions, 'assertionScope': ['project-create', 'project-update', 'negative-state-preservation',
                        'member-roles', 'last-admin', 'outsider-global-admin-acl', 'list-isolation', 'project-context'],
            'operations': sorted(set().union(*(c.operations for c in clients.values())))}


def catalog(root):
    path = Path(root) / 'docs/testing/backend-e2e-scenarios.md'
    return {m[0]: m[1] for m in re.findall(r'^## (E2E-\d+)\. (.+)$', path.read_text(), re.M)}


def add_cli(sub):
    e = sub.add_parser('e2e', help='Isolated local backend E2E or explicitly configured stage')
    e.add_argument('--tier', choices=('local', 'stage'), required=True)
    e.add_argument('--scenario', action='append', required=True)
    e.add_argument('--run-id', default=None, help='Unique 16 hex characters; generated if omitted')
    e.add_argument('--config', type=Path)
    cleanup = sub.add_parser('cleanup', help='Repeat cleanup using authenticated run-state from this checkout')
    cleanup.add_argument('--run-state', type=Path, required=True)
    cleanup.add_argument('--config', type=Path)


def stage_clients(config, state=None):
    actors, clients = {}, {}
    for role, a in config['actors'].items():
        token = os.environ.get(a['tokenEnv'])
        if not token:
            raise Blocked('Actor token unavailable: ' + role)
        client = Http(config['origin'], token)
        me = client.expect('GET', '/api/v1/users/me', 200).json()
        require(me.get('id') == a['id'], 'Configured actor identity mismatch')
        require(me.get('status') == 'ACTIVE', 'Configured actor is not ACTIVE')
        require(me.get('globalRole') == ('GLOBAL_ADMIN' if role == 'G' else 'USER'), 'Configured actor role mismatch')
        actors[role] = {'id': a['id']}
        clients[a['id']] = client
    if state and ({k: v['id'] for k, v in actors.items()} != state['actors']
                  or config['origin'] != state['origin'] or config['environmentId'] != state['environment']):
        raise Invalid('Stage environment/actor differs from authenticated run-state')
    return actors, clients


def finish_environment(store, state, report, local=None, clients=None, config=None):
    clients = clients or {}
    provisioned = bool(local and state.get('composeProject'))
    if provisioned or clients:
        try:
            report['serviceRevisionsAfter'] = local.revisions() if local else stage_revisions(config)
        except (Failure, Blocked, Invalid, KeyError, OSError, ValueError):
            report['results'].append({'checkId': 'revision-observation', 'status': 'BLOCKED',
                                      'reason': 'Final service revision observation unavailable'})
    # Cleanup has its own outcome. An observation failure must neither skip cleanup
    # nor overwrite successful cleanup with a fabricated cleanup failure.
    try:
        if provisioned:
            local.cleanup()
        elif clients:
            cleanup_projects(store, state, clients)
        else:
            state['cleanupStatus'] = 'PASS' if not state['resources'] and not state['pendingWrites'] else 'FAIL'
            store.save(state)
    except (Failure, Blocked, Invalid, KeyError, OSError, ValueError):
        state['cleanupStatus'] = 'FAIL'
        store.save(state)
        report['results'].append({'checkId': 'cleanup', 'status': 'FAIL',
                                  'reason': 'Cleanup failed; repeat cleanup from run-state'})
    if report['serviceRevisionsBefore']:
        report['results'].append(dict(revision_result(report['serviceRevisionsBefore'], report['serviceRevisionsAfter']),
                                      checkId='revision-stability'))


def _handle(args, root):
    store = StateStore(root)
    if args.command == 'cleanup':
        state = store.load(args.run_state)
        try:
            if state['tier'] == 'local':
                Local(root, store, state).cleanup()
            else:
                config = load_config(args.config)
                _, clients = stage_clients(config, state)
                cleanup_projects(store, state, clients)
        except (Invalid, Failure, Blocked) as exc:
            state['cleanupStatus'] = 'FAIL'
            store.save(state)
            return {'status': 'FAIL', 'reason': str(exc), 'runId': state['runId'], 'cleanupStatus': 'FAIL'}, 1
        return {'status': 'PASS', 'runId': state['runId'], 'cleanupStatus': 'PASS',
                'remainingOwnedResources': [{'type': 'archived-project', 'id': r['id']} for r in state['resources']]}, 0
    ids = list(dict.fromkeys(args.scenario))
    if set(ids) - set(catalog(root)):
        raise Invalid('Unknown scenario ID; use stable E2E-NN IDs from backend scenarios')
    run_id = args.run_id or secrets.token_hex(8)
    config = load_config(args.config) if args.tier == 'stage' else None
    state = store.create(run_id, args.tier, config['environmentId'] if config else 'isolated-compose', '')
    report = dict(schemaVersion=1, runId=run_id, startedAt=now(), environment=state['environment'],
                  tier=args.tier, results=[], serviceRevisionsBefore={}, serviceRevisionsAfter={}, **source(root))
    local, clients, actors = None, {}, {}
    old_handler = signal.getsignal(signal.SIGTERM)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        runnable = [i for i in ids if i in ('E2E-01', 'E2E-08')]
        for i in ids:
            if i not in runnable:
                report['results'].append({'scenarioId': i, 'checkId': i + '.manual', 'status': 'BLOCKED',
                    'reason': 'Manual scenario; required actors/capabilities not automated. See e2e.md',
                    'testCount': 0, 'assertionScope': []})
        if runnable:
            if args.tier == 'local':
                local = Local(root, store, state)
                actors = local.provision()
                clients = {a['id']: Http(state['origin'], a['token']) for a in actors.values()}
                report['serviceRevisionsBefore'] = local.revisions()
            else:
                report['serviceRevisionsBefore'] = stage_revisions(config)
                state['origin'] = config['origin']
                actors, clients = stage_clients(config)
                state['actors'] = {k: a['id'] for k, a in actors.items()}
                state['actor'] = actors['A']['id']
                store.save(state)
                Http(state['origin']).expect('GET', '/actuator/health', 200)
            for scenario in runnable:
                initial_assertions = ASSERTIONS
                try:
                    result = scenario_01(clients[actors['A']['id']]) if scenario == 'E2E-01' else scenario_08(store, state, clients, actors)
                except Blocked as exc:
                    result = {'status': 'BLOCKED', 'reason': str(exc), 'testCount': 0}
                except (Failure, KeyError, ValueError) as exc:
                    result = {'status': 'FAIL', 'reason': str(exc) if isinstance(exc, Failure) else 'Unexpected response schema: ' + (str(exc.args[0]) if isinstance(exc, KeyError) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', str(exc.args[0])) else type(exc).__name__), 'testCount': ASSERTIONS - initial_assertions}
                report['results'].append(dict(result, scenarioId=scenario, checkId=scenario + '.backend'))
    except KeyboardInterrupt:
        report['results'].append({'checkId': 'lifecycle', 'status': 'FAIL', 'reason': 'Run interrupted; cleanup attempted'})
    except (Failure, Blocked, Invalid, OSError, KeyError, ValueError) as exc:
        report['results'].append({'checkId': 'preflight', 'status': 'BLOCKED' if isinstance(exc, Blocked) else 'FAIL', 'reason': str(exc) if isinstance(exc, (Failure, Blocked, Invalid)) else 'Lifecycle error: ' + type(exc).__name__})
    finally:
        signal.signal(signal.SIGTERM, old_handler)
        finish_environment(store, state, report, local, clients, config)
    report['finishedAt'] = now()
    report['cleanupStatus'] = state['cleanupStatus']
    report['remainingOwnedResources'] = [{'type': 'archived-project' if r.get('archived') else r['type'], 'id': r['id']} for r in state['resources']]
    report['pendingWrites'] = len(state['pendingWrites'])
    report['artifactPaths'] = ['target/harness-e2e/' + run_id + '/report.json', 'target/harness-e2e/' + run_id + '/state.json']
    report['requestIds'] = [request_id for c in clients.values() for request_id in c.request_ids]
    report['coverage'] = {'httpOperations': sorted(set().union(*(c.operations for c in clients.values()), *(set(r.get('operations', [])) for r in report['results']))),
                          'fullyPassedScenarios': sum(r['status'] == 'PASS' for r in report['results'] if 'scenarioId' in r),
                          'selectedScenarios': len(ids)}
    code = next((n for s, n in [('FAIL', 1), ('BLOCKED', 3), ('PARTIAL', 4)] if any(r['status'] == s for r in report['results'])), 0)
    report['status'] = {0: 'PASS', 1: 'FAIL', 3: 'BLOCKED', 4: 'PARTIAL'}[code]
    private_json(store.path(run_id).parent / 'report.json', report)
    return report, code


def handle(args, root):
    store = StateStore(root)
    if args.command == 'cleanup':
        run_id = store.load(args.run_state)['runId']
    else:
        args.run_id = args.run_id or secrets.token_hex(8)
        run_id = args.run_id
    store.path(run_id)  # validate before using in lock path
    with mutex(store.directory / (run_id + '.lock')):
        return _handle(args, root)
