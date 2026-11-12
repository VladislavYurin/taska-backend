"""TAS-333 REST acceptance on an isolated, managed-trust stack with owned data."""
from concurrent.futures import ThreadPoolExecutor
import base64
import time
import uuid
from urllib.parse import urlencode

import e2e as e
import trusted_rpc_e2e as t


class TenantRenameLocal(t.TrustedLocal):
    modules = ('auth-service', 'api-gateway', 'tenant-service')

    def prepare_extra_databases(self):
        self.edge('api-gateway', 'api-gateway', 'gatewayRequest', 'gatewayRequest',
                  ['WEB_GLOBAL'], ['tenants:select', 'tenants:create', 'account:read'], mode='NONE')
        self.edge('api-gateway', 'api-gateway', 'gatewayRequest', 'gatewayRequest',
                  ['WEB_GLOBAL'], ['read', 'write'])
        self.edge('api-gateway', 'tenant-service', 'ru.taska.b2b.v1.TenantService/ListTenants',
                  'listTenants', ['WEB_GLOBAL'], ['tenants:select'], mode='NONE')
        for rpc, action in (('GetTenant', 'getTenant'), ('RenameTenant', 'renameTenant')):
            self.edge('api-gateway', 'tenant-service', 'ru.taska.b2b.v1.TenantService/' + rpc,
                      action, ['WEB_TENANT', 'USER_PAT', 'WEB_GLOBAL'], ['read', 'write'])
        self.refresh()

    def web_client(self, actor, tenant=None, membership=1, assurance='PASSWORD'):
        web, session = str(uuid.uuid4()), str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.web_sessions(id,user_id,state,assurance,auth_version,created_at,expires_at)"
                 " VALUES('%s','%s','ACTIVE','%s',1,now(),now()+interval '1 hour');" % (web, actor, assurance))
        claims = {'sub': actor, 'iss': 'taska-auth', 'aud': ['taska-api'], 'iat': int(time.time()),
                  'exp': int(time.time()) + 600, 'sid': web, 'kind': 'WEB_GLOBAL',
                  'account_generation': '1', 'scopes': ['tenants:select']}
        if tenant is not None:
            self.sql('auth_db', "INSERT INTO taska.tenant_sessions(id,web_session_id,user_id,tenant_id,membership_version,created_at,expires_at)"
                     " VALUES('%s','%s','%s','%s',%d,now(),now()+interval '1 hour');"
                     % (session, web, actor, tenant, membership))
            claims.update(sid=session, kind='WEB_TENANT', tenant_id=tenant,
                          membership_generation=str(membership), scopes=['read', 'write'])
        def b64(value):
            return base64.urlsafe_b64encode(value).rstrip(b'=')

        data = b64(e.canonical({'alg': 'EdDSA', 'kid': 'owned-access', 'typ': 'JWT'})) + b'.' + b64(e.canonical(claims))
        source, signature = self.material / 'rename-jwt-input', self.material / 'rename-jwt-signature'
        source.write_bytes(data)
        self.command('java', str(self.root / 'scripts/harness/RpcFixtureKey.java'), 'sign',
                     str(self.material / 'auth-service/jwt.pem'), str(source), str(signature))
        return e.Http(self.state['origin'], (data + b'.' + b64(signature.read_bytes())).decode())

    def user(self, email, role='USER'):
        actor = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role)"
                 " VALUES('%s','%s','Owned fixture','ACTIVE','%s');" % (actor, email, role))
        return actor

    @staticmethod
    def uncached(response):
        policy = response.header('Cache-Control') or ''
        e.require('private' in policy and 'no-store' in policy, 'Company response can be cached')
        return response.json()

    @staticmethod
    def headers(version, key=None):
        return {'If-Match': '"' + version + '"', 'Idempotency-Key': key or str(uuid.uuid4())}

    def tenant_snapshot(self):
        return self.sql('tenant_db', """
            SELECT jsonb_build_object(
              'tenants',(SELECT jsonb_agg(to_jsonb(t)-'name'-'version'-'updated_at' ORDER BY id)
                         FROM taska.tenants t),
              'memberships',(SELECT jsonb_agg(to_jsonb(m) ORDER BY tenant_id,user_id)
                             FROM taska.tenant_memberships m),
              'quota',(SELECT jsonb_agg(to_jsonb(q) ORDER BY tenant_id,resource,scope_id)
                       FROM taska.quota_counters q));
            """).strip()

    def scenarios(self):
        owner_id, clients = self.seed()
        first, owner = clients[0]
        second, other_scope = clients[1]
        path = '/api/v1/tenants/' + first
        list_path = '/api/v1/tenants'
        global_owner = self.web_client(owner_id)
        member_id = self.user('rename-member@harness.invalid')
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                 " VALUES('%s','%s','MEMBER','ACTIVE');" % (first, member_id))
        member = self.web_client(member_id, first)
        member_list = self.web_client(member_id)
        admin_id = self.user('rename-global-admin@harness.invalid', 'GLOBAL_ADMIN')
        admin = self.web_client(admin_id, assurance='ADMIN_EMAIL_MFA')
        t.wait_protected_read(owner, path, 200)
        initial = self.uncached(owner.expect('GET', path, 200))
        e.require(initial['id'] == first and initial['name'] == 'Owned', 'Unexpected details fixture')
        page = self.uncached(global_owner.expect('GET', list_path + '?limit=1', 200))
        e.require(len(page['items']) == 1 and page.get('nextCursor'), 'First list page is not bounded')
        next_page = self.uncached(global_owner.expect('GET', list_path + '?' + urlencode(
            {'limit': 1, 'cursor': page['nextCursor']}), 200))
        e.require({page['items'][0]['id'], next_page['items'][0]['id']} == {first, second},
                  'List cursor repeated or skipped a company')
        e.require('nextCursor' not in next_page, 'The last page contains an unexpected cursor')
        limited = self.uncached(member_list.expect('GET', list_path, 200))
        e.require([item['id'] for item in limited['items']] == [first], 'List exposed another membership')
        member_list.expect('GET', list_path + '?' + urlencode({'cursor': page['nextCursor']}), 400)
        version = initial['version']
        member.expect('PATCH', path, 403, {'name': 'Forbidden'}, self.headers(version))
        other_scope.expect('PATCH', path, 403, {'name': 'Foreign tenant'}, self.headers(version))
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET role='TENANT_ADMIN',version=version+1"
                 " WHERE tenant_id='%s' AND user_id='%s';" % (first, member_id))
        # Start the preservation snapshot after the fixture's deliberate role change.
        before = self.tenant_snapshot()
        self.web_client(member_id, first, 2).expect('PATCH', path, 403,
                                                    {'name': 'Non-owner admin'}, self.headers(version))
        owner.expect('PATCH', path, 428, {'name': 'Missing version'},
                     {'Idempotency-Key': str(uuid.uuid4())})
        owner.expect('PATCH', path, 400, {'name': 'Invalid', 'slug': 'injected'}, self.headers(version))
        owner.expect('PATCH', path, 400, {'name': '  '}, self.headers(version))
        unchanged = self.uncached(owner.expect('PATCH', path, 200, {'name': 'Owned'}, self.headers(version)))
        e.require(unchanged['version'] == version, 'No-op advanced the version')

        mutation = self.headers(version)
        renamed = self.uncached(owner.expect('PATCH', path, 200, {'name': 'Renamed'}, mutation))
        replay = self.uncached(owner.expect('PATCH', path, 200, {'name': 'Renamed'}, mutation))
        e.require(replay == renamed and int(renamed['version']) == int(version) + 1, 'Rename replay changed state')
        owner.expect('PATCH', path, 412, {'name': 'Stale'}, self.headers(version))
        owner.expect('PATCH', path, 409, {'name': 'Changed receipt'}, mutation)
        details = self.uncached(owner.expect('GET', path, 200))
        listed = self.uncached(global_owner.expect('GET', list_path, 200))
        e.require(details == renamed and any(item == renamed for item in listed['items']),
                  'List/details did not observe the committed rename')

        def race(name):
            contender = e.Http(self.state['origin'], owner.token)
            return contender.request('PATCH', path, {'name': name}, self.headers(renamed['version']))

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(race, ('Concurrent A', 'Concurrent B')))
        e.require(sorted(result.status for result in results) == [200, 412], 'Rename race lost the version guard')
        winner = next(result.json() for result in results if result.status == 200)
        admin_details = self.uncached(admin.expect('GET', path, 200))
        e.require(admin_details == winner, 'Verified admin did not get current details')
        denied = self.web_client(admin_id).request('PATCH', path, {'name': 'No MFA'},
                                                   self.headers(winner['version']))
        e.require(denied.status in (401, 403), 'Administrative rename admitted a session without MFA')
        updated = self.uncached(admin.expect('PATCH', path, 200, {'name': 'Admin rename'},
                                             self.headers(winner['version'])))
        e.require(updated['name'] == 'Admin rename', 'Verified admin did not rename the company')
        e.require(self.tenant_snapshot() == before, 'Rename changed company identity, memberships or quotas')
        events = self.sql('tenant_db', "SELECT count(*) FROM taska.outbox_events"
                          " WHERE event_type='TenantRenamed' AND tenant_id='%s';" % first).strip()
        audit = self.sql('tenant_db', "SELECT count(*) FROM taska.tenant_rename_audit"
                         " WHERE administrative AND actor_id='%s' AND result='SUCCEEDED';" % admin_id).strip()
        e.require(events == '3' and audit == '1', 'Rename event or administrative audit did not match effects')
        return {'tenantRenameRest': 'PASS', 'freshListAndDetails': 'PASS',
                'ownerAndAdminAuthority': 'PASS', 'versionAndRetry': 'PASS',
                'concurrencyAndStatePreservation': 'PASS'}


if __name__ == '__main__':
    t.TrustedLocal = TenantRenameLocal
    t.main()
