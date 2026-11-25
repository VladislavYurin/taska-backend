"""TAS-321: справочник компаний через REST, реальные RPC и собственные фикстуры."""
from urllib.parse import urlencode

import e2e as e
import trusted_rpc_e2e as t
from tenant_rename_e2e import TenantRenameLocal


class TenantDirectoryLocal(TenantRenameLocal):
    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        self.edge('api-gateway', 'tenant-service',
                  'ru.taska.b2b.v1.TenantService/ListTenantMembers', 'listTenantMembers',
                  ['WEB_TENANT'], ['read', 'write'])
        self.edge('api-gateway', 'auth-service',
                  'ru.taska.profile.v1.ProfileService/GetUserDetailsByIds', 'getUserDetailsByIds',
                  ['WEB_TENANT'], ['read', 'write'])
        self.edge('auth-service', 'tenant-service',
                  'ru.taska.b2b.v1.TenantMembershipService/BatchValidateMemberships',
                  'batchValidateMemberships', ['SERVICE'], ['memberships:read'], 'membership')
        self.refresh()

    def scenarios(self):
        owner_id, clients = self.seed()
        company_a, owner = clients[0]
        company_b, other = clients[1]
        member_a = self.user('directory-a@harness.invalid')
        member_b = self.user('directory-b@harness.invalid')
        for company, actor in ((company_a, member_a), (company_b, member_b)):
            self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                     " VALUES('%s','%s','MEMBER','ACTIVE');" % (company, actor))
        path = '/api/v1/tenants/' + company_a + '/members'
        t.wait_protected_read(owner, path, 200)
        page = owner.expect('GET', path + '?limit=1', 200).json()
        e.require(len(page['items']) == 1 and page.get('nextCursor'), 'Missing bounded member page')
        following = owner.expect('GET', path + '?' + urlencode(
            {'limit': 1, 'cursor': page['nextCursor']}), 200).json()
        members = page['items'] + following['items']
        e.require({item['userId'] for item in members} == {owner_id, member_a}, 'Directory leaked or lost users')
        e.require(all(item['tenantId'] == company_a and item['displayName'] == ('Owned' if item['userId'] == owner_id else 'Owned fixture')
                      and item['email'] for item in members), 'Incomplete member profiles')
        other.expect('GET', path, 403)
        query = '/api/v1/users?' + urlencode({'query': 'directory-', 'pageSize': 1})
        result = owner.expect('GET', query, 200).json()
        e.require([item['id'] for item in result['items']] == [member_a]
                  and result['hasMore'] is False, 'Search includes foreign company or wrong hasMore')
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1"
                 " WHERE tenant_id='%s' AND user_id='%s';" % (company_a, owner_id))
        e.require(owner.request('GET', path).status in (401, 403), 'Revoked membership still lists members')
        e.require(owner.request('GET', query).status in (401, 403), 'Revoked membership still searches')
        return {'checks': ['member-pagination', 'profile-composition', 'foreign-tenant-denied',
                           'search-tenant-isolation', 'search-has-more', 'membership-revocation']}


if __name__ == '__main__':
    t.main(scenario='tenant-directory', local_cls=TenantDirectoryLocal)
