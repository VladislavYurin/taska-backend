"""TAS-497: public capabilities with seven packaged applications and owned fixtures only.

Real auth primary, tenant PostgreSQL/RLS, public configuration, billing entitlement/catalog,
managed mTLS/JWS and bounded caches. Synthetic subscription fixtures are not payment E2E.
"""
import base64
import json
import re
import time
import uuid

import e2e as e
import trusted_rpc_e2e as t


class CapabilitiesLocal(t.TrustedLocal):
    modules = t.MODULES + ('billing-service',)
    identities = t.IDENTITIES + ('billing-service',)

    def edge(self, issuer, audience, rpc, action, kinds, scopes, purpose='USER', mode='BOUND'):
        super().edge(issuer, audience, rpc, action, kinds, scopes, purpose, mode)
        if purpose in ('billing', 'admin'):
            self.snapshot['edges'][-1]['serviceIdentities'] = ['tenant-service']

    def configure_service(self, name, env, props):
        if name == 'billing-service':
            env.update({'SPRING_R2DBC_USERNAME': 'taska_billing_app', 'SPRING_R2DBC_PASSWORD': self.password,
                        'SPRING_LIQUIBASE_USER': 'taska_billing_deployer', 'SPRING_LIQUIBASE_PASSWORD': self.password})
        if name == 'api-gateway':
            props['taska.capabilities.tenant-port'] = '9090'
        if name == 'tenant-service':
            props['taska.capabilities.billing-port'] = '9090'

    def prepare_extra_databases(self):
        self.sql('postgres', 'CREATE DATABASE billing_db;')
        self.compose('exec', '-T', 'database', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'taska', '-d', 'billing_db',
                     '-v', 'service=billing', '-v', 'schema=taska', '-f', '/tmp/bootstrap.psql')
        self.sql('billing_db', "CREATE SCHEMA billing_liquibase AUTHORIZATION taska_billing_migrator;ALTER ROLE taska_billing_app PASSWORD '%s';ALTER ROLE taska_billing_deployer PASSWORD '%s';" % (self.password, self.password))
        self.edge('api-gateway', 'tenant-service', 'ru.taska.b2b.v1.TenantCapabilitiesService/GetTenantCapabilities',
                  'getTenantCapabilities', ['WEB_TENANT', 'USER_PAT'], ['read', 'write'])
        self.edge('tenant-service', 'tenant-service', 'ru.taska.b2b.v1.PlatformService/GetPlatformConfigurationSnapshot',
                  'getPlatformConfigurationSnapshot', ['SERVICE'], ['read'], 'admin', 'NONE')
        self.edge('tenant-service', 'billing-service', 'ru.taska.b2b.v1.EntitlementService/GetEntitlementSnapshot',
                  'getEntitlementSnapshot', ['SERVICE'], ['read'], 'billing')
        self.edge('tenant-service', 'billing-service', 'ru.taska.b2b.v1.BillingService/ReadReferencedPlanVersion',
                  'readReferencedPlanVersion', ['SERVICE'], ['read', 'catalog:read'], 'billing', 'NONE')
        self.refresh()

    def member_client(self, tenant, actor, generation, scopes=None):
        web, session = str(uuid.uuid4()), str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.web_sessions(id,user_id,state,assurance,auth_version,created_at,expires_at) VALUES('%s','%s','ACTIVE','PASSWORD',1,now(),now()+interval '1 hour');INSERT INTO taska.tenant_sessions(id,web_session_id,user_id,tenant_id,membership_version,created_at,expires_at) VALUES('%s','%s','%s','%s',%d,now(),now()+interval '1 hour');" % (web, actor, session, web, actor, tenant, generation))
        claims = {'sub': actor, 'iss': 'taska-auth', 'aud': ['taska-api'], 'iat': int(time.time()), 'exp': int(time.time()) + 600,
                  'sid': session, 'tenant_id': tenant, 'kind': 'WEB_TENANT', 'account_generation': '1',
                  'membership_generation': str(generation), 'scopes': ['read', 'write'] if scopes is None else scopes}
        b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b'=')
        data = b64(e.canonical({'alg': 'EdDSA', 'kid': 'owned-access', 'typ': 'JWT'})) + b'.' + b64(e.canonical(claims))
        source, signature = self.material / 'member-input', self.material / 'member-signature'
        source.write_bytes(data)
        self.command('java', str(self.root / 'scripts/harness/RpcFixtureKey.java'), 'sign', str(self.material / 'auth-service/jwt.pem'), str(source), str(signature))
        return e.Http(self.state['origin'], (data + b'.' + b64(signature.read_bytes())).decode()), session

    @staticmethod
    def action(result, name):
        return next(item for item in result['items'] if item['action'] == name)

    def capabilities(self, client, tenant):
        response = client.expect('GET', '/api/v1/tenants/' + tenant + '/capabilities', 200)
        cache = next((v for k, v in response.headers.items() if k.lower() == 'cache-control'), '')
        e.require('no-store' in cache and 'private' in cache, 'Capabilities can enter an HTTP shared cache')
        result = response.json()
        e.require(result['tenantId'] == tenant, 'Public capabilities changed target')
        e.require(set(result) == {'tenantId', 'actorUserId', 'policyVersion', 'entitlementVersion', 'membershipVersion',
                                  'flagVersion', 'evaluatedAt', 'effectiveAt', 'expiresAt', 'items', 'durationsSeconds'}, 'Unexpected public fields')
        e.require(result['durationsSeconds']['WIRE_RECONCILIATION_TARGET_SECONDS'] == 86400, 'Missing public bank reconciliation target')
        from datetime import datetime
        lease = datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', result['expiresAt']).replace('Z', '+00:00')) - datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', result['evaluatedAt']).replace('Z', '+00:00'))
        e.require(0 < lease.total_seconds() <= 30, 'Public lease not hard bounded')
        return result

    def scenarios(self):
        actor, clients = self.seed()
        first, second = clients[0][0], clients[1][0]
        for tenant, plan, mode in ((first, '00000000-0000-8000-8000-000000000001', 'FREE'),
                                  (second, '00000000-0000-8000-8000-000000000002', 'PAID')):
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'%s' FROM taska.catalog_plan_versions p WHERE id='%s';" % (tenant, mode, plan))
        before = self.counts()
        attempts = []
        for tenant, client in clients:
            attempts.append(t.wait_protected_read(client, '/api/v1/tenants/' + tenant + '/capabilities', 200))
        a = self.capabilities(clients[0][1], first)
        b = self.capabilities(clients[1][1], second)
        e.require(a['actorUserId'] == actor and b['actorUserId'] == actor, 'Public actor differs from primary')
        e.require(a['flagVersion'] == b['flagVersion'], 'Company overrides were invented')
        e.require(self.action(a, 'tenant.pause')['allowed'], 'Verified owner write scope was lost')
        for _ in range(3):
            self.refresh()
            self.confirm_delivery()
            refreshed = self.capabilities(clients[0][1], first)
            e.require(refreshed['flagVersion'] == a['flagVersion'] and self.action(refreshed, 'tenant.pause')['allowed'], 'Trust publication interrupted an unchanged capability')
        e.require(not self.action(a, 'tenant.transfer_ownership')['allowed'], 'FREE company may transfer')
        e.require('PAYMENT_EXPIRED' in self.action(b, 'tenant.transfer_ownership')['restrictions'], 'Actual expired billing state was omitted')
        # Existing entitlements keep immutable terms when a later version is published.
        self.sql('billing_db', "INSERT INTO taska.catalog_plan_versions SELECT (jsonb_populate_record(NULL::taska.catalog_plan_versions,to_jsonb(p)||jsonb_build_object('id','00000000-0000-8000-8000-000000000003','version',2,'created_at',now(),'published_at',now()))).* FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';")
        e.require(self.capabilities(clients[0][1], first)['entitlementVersion'] == a['entitlementVersion'], 'Publishing another offer changed historical terms')
        reader, _ = self.member_client(first, actor, 1, ['read'])
        read_view = self.capabilities(reader, first)
        e.require(not self.action(read_view, 'tenant.pause')['allowed'] and 'SCOPE_DENIED' in self.action(read_view, 'tenant.pause')['denialCodes'], 'Read-only credential acquired a write capability')
        self.sql('billing_db', "UPDATE taska.subscriptions SET selected_plan_version_id=p.id,selected_terms_snapshot=to_jsonb(p),mode='FREE' FROM taska.catalog_plan_versions p WHERE tenant_id='%s' AND p.id='00000000-0000-8000-8000-000000000001';" % second)
        repriced = self.capabilities(clients[1][1], second)
        e.require(repriced['entitlementVersion'] != b['entitlementVersion'] and 'PAYMENT_EXPIRED' not in self.action(repriced, 'tenant.transfer_ownership')['restrictions'], 'Tariff change reused an expired commercial decision')
        clients[0][1].expect('GET', '/api/v1/tenants/' + second + '/capabilities', 403)
        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','member@harness.invalid','Owned member','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');" % (first, member))
        admin, _ = self.member_client(first, member, 1)
        e.require(self.action(self.capabilities(admin, first), 'membership.role_update')['allowed'], 'Current tenant admin not recognized')
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET role='MEMBER',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (first, member))
        e.require(admin.request('GET', '/api/v1/tenants/' + first + '/capabilities').status in (401, 403), 'Old membership generation reused a warm allow')
        current, session = self.member_client(first, member, 2)
        e.require(not self.action(self.capabilities(current, first), 'membership.role_update')['allowed'], 'Demoted member reused admin capability')
        self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,2,value_type,'false'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='OWNER_PAUSE';" % actor)
        changed = self.capabilities(clients[0][1], first)
        e.require(changed['flagVersion'] != a['flagVersion'], 'Versioned configuration was not rechecked')
        e.require(not self.action(changed, 'tenant.pause')['allowed'] and 'CAPABILITY_DISABLED' in self.action(changed, 'tenant.pause')['denialCodes'], 'Flag off did not deny a new action')
        self.sql('auth_db', "UPDATE taska.tenant_sessions SET revoked_at=now() WHERE id='%s';" % session)
        e.require(current.request('GET', '/api/v1/tenants/' + first + '/capabilities').status in (401, 403), 'Revoked credential reused warm capability')
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';" % actor)
        clients[0][1].expect('GET', '/api/v1/tenants/' + first + '/capabilities', 403)
        self.sql('auth_db', "UPDATE taska.users SET global_role='USER' WHERE id='%s';" % actor)
        self.compose('stop', 'billing-service')
        clients[0][1].expect('GET', '/api/v1/tenants/' + first + '/capabilities', 503)
        e.require(self.counts() == before, 'Capabilities changed domain/outbox state')
        return {'publicCapabilities': 'PASS', 'primaryRoleMembershipCredential': 'PASS', 'currentFlagsBilling': 'PASS',
                'foreignDenial': 'PASS', 'warmCacheOutage': 'PASS', 'statePreservation': 'PASS', 'protectedReadinessAttempts': attempts}


def main():
    # Reuse the existing signed cleanup path and final report lifecycle unchanged.
    t.TrustedLocal = CapabilitiesLocal
    t.main()


if __name__ == '__main__':
    main()
