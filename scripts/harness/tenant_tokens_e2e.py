"""TAS-542: owned REST -> mTLS/JWS -> primary facts -> PostgreSQL token acceptance.

Uses the capabilities fixture and its signed cleanup lifecycle. No shared environment is used.
"""
import uuid
import e2e as e
import trusted_rpc_e2e as t
from capabilities_e2e import CapabilitiesLocal


class TenantTokensLocal(CapabilitiesLocal):
    def edge(self, issuer, audience, rpc, action, kinds, scopes, purpose='USER', mode='BOUND'):
        super().edge(issuer, audience, rpc, action, kinds, scopes, purpose, mode)
        if purpose != 'USER':
            self.snapshot['edges'][-1]['serviceIdentities'] = [issuer]

    def configure_service(self, name, env, props):
        super().configure_service(name, env, props)
        for service in self.modules:
            props['taska.domain.' + service + '.port'] = '9090'
        if name == 'auth-service':
            props['taska.b2b.tenant-tokens.enabled'] = 'true'

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        self.edge('api-gateway', 'auth-service',
                  'ru.taska.b2b.v1.TenantPersonalTokenService/CreateTenantPersonalToken',
                  'createTenantPersonalToken', ['WEB_TENANT'], ['write'])
        self.edge('auth-service', 'tenant-service',
                  'ru.taska.b2b.v1.TenantMembershipService/BatchValidateMemberships',
                  'batchValidateMemberships', ['SERVICE'], ['memberships:read'], 'membership')
        self.edge('auth-service', 'tenant-service',
                  'ru.taska.b2b.v1.PlatformService/GetPlatformConfigurationSnapshot',
                  'getPlatformConfigurationSnapshot', ['SERVICE'], ['read'], 'admin', 'NONE')
        self.edge('auth-service', 'billing-service',
                  'ru.taska.b2b.v1.EntitlementService/GetEntitlementSnapshot',
                  'getEntitlementSnapshot', ['SERVICE'], ['read'], 'billing')
        self.edge('auth-service', 'billing-service',
                  'ru.taska.b2b.v1.BillingService/ReadReferencedPlanVersion',
                  'readReferencedPlanVersion', ['SERVICE'], ['read', 'catalog:read'], 'billing', 'NONE')
        self.refresh()

    def flag(self, actor, key, value, version):
        # Only this run's newly created configuration database and actors.
        e.require(key in ('FREE_PERSONAL_API_TOKENS', 'PERSONAL_API_TOKENS'), 'Unknown fixture flag')
        self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,%d,value_type,'%s'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='%s';" % (version, 'true' if value else 'false', actor, key))

    def count(self):
        return int(self.sql('auth_db', 'SELECT count(*) FROM taska.personal_access_tokens').strip())

    def scenarios(self):
        actor, clients = self.seed()
        for tenant, client in clients:
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            t.wait_protected_read(client, '/api/v1/tenants/' + tenant + '/capabilities', 200)
        tenant, client = clients[0]
        other, other_client = clients[1]
        path = '/api/v1/account/tenant-tokens'
        body = {'tenantId': tenant, 'name': 'Owned CI', 'scopes': ['write'], 'expiresInDays': 30}
        key = {'Idempotency-Key': str(uuid.uuid4())}
        denied = client.expect('POST', path, 403, body, key).json()
        e.require(denied['code'] == 'CAPABILITY_DISABLED', 'FREE flag did not deny issuance')
        e.require(self.count() == 0, 'Denied issuance changed token state')
        self.flag(actor, 'FREE_PERSONAL_API_TOKENS', True, 2)
        response = client.expect('POST', path, 201, body, key)
        e.require('no-store' in next((v for k, v in response.headers.items() if k.lower() == 'cache-control'), ''), 'Secret response may be cached')
        created = response.json()
        e.require(created['metadata']['tenantId'] == tenant, 'Issuance lost company')
        e.require(set(created['metadata']['scopes']) == {'read', 'write'}, 'write did not include read')
        replay = client.expect('POST', path, 201, body, key).json()
        e.require(replay == created and self.count() == 1, 'Idempotent replay changed token/secret')
        foreign = dict(body, tenantId=other)
        refusal = client.expect('POST', path, 403, foreign, {'Idempotency-Key': str(uuid.uuid4())}).json()
        e.require(refusal['code'] == 'TENANT_MISMATCH' and self.count() == 1, 'Foreign company admitted issuance')
        listed = client.expect('GET', '/api/v1/users/me/access-tokens', 200).json()
        item = next(x for x in listed['items'] if x['id'] == created['metadata']['id'])
        e.require(item['tenantId'] == tenant and 'secret' not in item, 'List lost company or disclosed secret')
        pat = e.Http(self.state['origin'], created['secret'])
        e.require(pat.request('POST', path, body, {'Idempotency-Key': str(uuid.uuid4())}).status == 403, 'PAT issued another PAT')
        client.expect('DELETE', '/api/v1/users/me/access-tokens/' + item['id'], 204)
        e.require(pat.request('GET', '/api/v1/tenants/' + tenant + '/capabilities').status in (401, 403), 'Revoked PAT still authenticates')
        self.flag(actor, 'PERSONAL_API_TOKENS', False, 2)
        caps = self.capabilities(other_client, other)
        e.require(self.action(caps, 'pat.create')['disabledFlags'] == ['PERSONAL_API_TOKENS'], 'Capability lost global flag reason')
        other_client.expect('POST', path, 403, dict(body, tenantId=other), {'Idempotency-Key': str(uuid.uuid4())})
        e.require(self.count() == 1, 'Rejected requests changed persisted token count')
        return {'restIssuancePrimaryFacts': 'PASS', 'sameSecretReplay': 'PASS',
                'companyMismatchNoWrite': 'PASS', 'metadataCompany': 'PASS',
                'patCannotIssue': 'PASS', 'revocation': 'PASS', 'flagReasons': 'PASS'}


def main():
    t.TrustedLocal = TenantTokensLocal
    t.main()


if __name__ == '__main__':
    main()
