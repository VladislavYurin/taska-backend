"""TAS-480: owned native REST/root/content/mTLS restriction acceptance; no PSP charges."""
from concurrent.futures import ThreadPoolExecutor
import json
import uuid

import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal
from tenant_rename_e2e import TenantRenameLocal


class TenantRestrictionsLocal(IssueAclLocal):
    web_client = TenantRenameLocal.web_client
    user = TenantRenameLocal.user

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        TenantRenameLocal.prepare_extra_databases(self)
        for service, method, action in (('TenantService', 'PauseTenant', 'pauseTenant'),
                ('TenantService', 'ResumeTenant', 'resumeTenant'),
                ('PlatformService', 'SetPlatformBlock', 'setPlatformBlock')):
            self.edge('api-gateway', 'tenant-service', 'ru.taska.b2b.v1.' + service + '/' + method,
                action, ['WEB_TENANT', 'USER_PAT', 'WEB_GLOBAL'], ['read', 'write'])
        self.refresh()

    @staticmethod
    def headers(version, key=None):
        return {'If-Match': '"' + str(version) + '"', 'Idempotency-Key': key or str(uuid.uuid4())}

    def scenarios(self):
        actor, clients = self.seed()
        projects = []
        for tenant, client in clients:
            client.timeout = 60
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            projects.append(client.expect('POST', '/api/v1/projects', 201,
                {'projectKey': 'RST', 'name': 'Owned restrictions'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id'])
        tenant, owner = clients[0]
        foreign_tenant, other = clients[1]
        project, foreign = projects
        path = '/api/v1/tenants/' + tenant
        pause_path = path + '/pause'
        project_path = '/api/v1/projects/' + project
        self.ready_workflow(owner, project)
        issue_payload = {'issueType': 'TASK', 'summary': 'Owned restriction fixture',
            'description': 'Retained under restrictions', 'priority': 'MEDIUM'}
        issue = owner.expect('POST', project_path + '/issues', 201, issue_payload,
            {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
        content_paths = (project_path, '/api/v1/issues/' + issue, project_path + '/workflows')
        baseline = self.sql('billing_db', "SELECT jsonb_build_object('subscriptions',(SELECT jsonb_agg(to_jsonb(s) ORDER BY tenant_id) FROM taska.subscriptions s),'periods',(SELECT count(*) FROM taska.subscription_periods),'attempts',(SELECT count(*) FROM taska.payment_attempts));").strip()
        admin_id = self.user('restriction-admin@harness.invalid', 'GLOBAL_ADMIN')
        weak_admin = self.web_client(admin_id)
        admin = self.web_client(admin_id, assurance='ADMIN_EMAIL_MFA')
        block_path = '/api/v1/admin/tenants/' + tenant + '/platform-block'
        member = self.user('restriction-member@harness.invalid')
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');" % (tenant, member))
        ordinary, session = self.member_client(tenant, member, 1)
        initial = owner.expect('GET', path, 200).json()
        ordinary.expect('PUT', pause_path, 403, {'confirmed': True}, self.headers(initial['version']))
        owner.expect('PUT', pause_path, 428, {'confirmed': True}, {'Idempotency-Key': str(uuid.uuid4())})
        key = str(uuid.uuid4())
        original = self.headers(initial['version'], key)
        paused = owner.expect('PUT', pause_path, 200, {'confirmed': True}, original).json()
        e.require(paused['restrictions'] == ['OWNER_PAUSE'], 'Pause was not returned as an independent cause')
        e.require(owner.expect('PUT', pause_path, 200, {'confirmed': True}, original).json() == paused, 'Pause replay changed response')
        owner.expect('DELETE', pause_path, 409, headers=original)
        for content_path in content_paths:  # READ_WHILE_RESTRICTED defaults to true.
            owner.expect('GET', content_path, 200)
        denied = owner.expect('PATCH', project_path, 403, {'name': 'Denied'}).json()
        e.require('OWNER_PAUSE' in json.dumps(denied), 'Write denial lost root reason')
        denied = owner.expect('POST', project_path + '/issues', 403, issue_payload,
            {'Idempotency-Key': str(uuid.uuid4())}).json()
        e.require('OWNER_PAUSE' in json.dumps(denied), 'Issue write lost current root reason')
        other.expect('GET', '/api/v1/projects/' + foreign, 200)
        self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,2,value_type,'false'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='READ_WHILE_RESTRICTED';" % actor)
        for content_path in content_paths:
            denied = owner.expect('GET', content_path, 403).json()
            e.require('OWNER_PAUSE' in json.dumps(denied), 'Closed content read lost root reason')
        owner.expect('GET', path, 200)
        # Membership has an existing internal RPC; its public REST adapter is a separate task.
        financial = self.action(self.capabilities(owner, tenant), 'billing.read')
        e.require(financial['allowed'], 'Read flag hid permitted financial recovery capability')
        payload = {'enabled': True, 'reason': 'Private owned operator evidence', 'confirmed': True}
        weak_admin.expect('PUT', block_path, 403, payload, self.headers(paused['version']))
        owner.expect('PUT', block_path, 403, payload, self.headers(paused['version']))
        blocked = admin.expect('PUT', block_path, 200, payload, self.headers(paused['version'])).json()
        e.require(set(blocked['restrictions']) == {'OWNER_PAUSE', 'PLATFORM_BLOCK'}, 'Platform block replaced owner pause')
        owner.expect('DELETE', pause_path, 403, headers=self.headers(blocked['version']))  # Approved OWNER_CONTROL; TAS-535 tracks proposal.
        unblocked = admin.expect('PUT', block_path, 200, dict(payload, enabled=False), self.headers(blocked['version'])).json()
        e.require(unblocked['restrictions'] == ['OWNER_PAUSE'], 'Clearing platform block removed another reason')
        owner.expect('DELETE', pause_path, 412, headers=self.headers(initial['version']))
        resumed = owner.expect('DELETE', pause_path, 200, headers=self.headers(unblocked['version'])).json()
        e.require(not resumed['restrictions'], 'Resume retained the owner reason')
        for content_path in content_paths:
            owner.expect('GET', content_path, 200)
        e.require(owner.expect('PUT', pause_path, 200, {'confirmed': True}, original).json() == paused, 'Receipt changed after subsequent resume')
        e.require(not owner.expect('GET', path, 200).json()['restrictions'], 'Receipt repeated its pause effect')
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(owner.request, 'PUT', pause_path, {'confirmed': True}, self.headers(resumed['version'])) for _ in range(2)]
            responses = [f.result() for f in futures]
        e.require(sorted(r.status for r in responses) == [200, 412], 'Concurrent pause overwrote a version')
        current = owner.expect('GET', path, 200).json()
        self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,2,value_type,'false'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='OWNER_PAUSE';" % actor)
        owner.expect('DELETE', pause_path, 200, headers=self.headers(current['version']))
        e.require(not other.expect('GET', '/api/v1/tenants/' + foreign_tenant, 200).json()['restrictions'], 'Foreign company state changed')
        self.sql('auth_db', "UPDATE taska.tenant_sessions SET revoked_at=now() WHERE id='%s';" % session)
        e.require(ordinary.request('PUT', pause_path, {'confirmed': True}, self.headers(current['version'])).status in (401, 403), 'Revoked credential retained lifecycle rights')
        final = self.sql('billing_db', "SELECT jsonb_build_object('subscriptions',(SELECT jsonb_agg(to_jsonb(s) ORDER BY tenant_id) FROM taska.subscriptions s),'periods',(SELECT count(*) FROM taska.subscription_periods),'attempts',(SELECT count(*) FROM taska.payment_attempts));").strip()
        e.require(final == baseline, 'Tenant commands changed paid period/subscription or created a payment')
        e.require(self.sql('tenant_db', "SELECT count(*) FROM taska.outbox_events WHERE payload::text LIKE '%Private owned operator evidence%';").strip() == '0', 'Private admin reason leaked into public event')
        e.require(int(self.sql('tenant_db', "SELECT count(*) FROM taska.tenant_restriction_audit WHERE administrative AND tenant_id='%s';" % tenant).strip()) >= 2, 'Administrative block lacks audit')
        return {'scenario': 'tenant-restrictions', 'independentReasons': 'PASS', 'currentOwnerAndAdministrativeMfa': 'PASS',
            'contentReadFlagAndManagement': 'PASS', 'financialCapabilities': 'PASS', 'casReplayAndConcurrency': 'PASS',
            'billingStatePreserved': 'PASS', 'privateAuditReason': 'PASS',
            'scope': 'Native REST/mTLS root/content; autopay admission/reconciliation uses separate PG tests, no live PSP charges.'}


if __name__ == '__main__':
    t.TrustedLocal = TenantRestrictionsLocal
    t.main()
