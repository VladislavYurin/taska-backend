"""TAS-343: изолированный CRUD задач с реальными сервисами авторизации, арендаторов, проектов, workflow и биллинга."""
import base64
import json
import uuid
import zipfile

import e2e as e
import trusted_rpc_e2e as t
from workflow_acl_e2e import WorkflowAclLocal


class IssueAclLocal(WorkflowAclLocal):
    def configure_container(self, name, service):
        super().configure_container(name, service)
        if name == 'issue-service':
            service.setdefault('ports', []).append('127.0.0.1::9090')

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        for rpc, action, audience, purpose, mode, scopes in (
            ('ru.taska.b2b.v1.TenantMembershipService/BatchValidateMemberships', 'batchValidateMemberships', 'tenant-service', 'membership', 'BOUND', ['memberships:read']),
            ('ru.taska.b2b.v1.PlatformService/GetPlatformConfigurationSnapshot', 'getPlatformConfigurationSnapshot', 'tenant-service', 'admin', 'NONE', ['read']),
            ('ru.taska.b2b.v1.EntitlementService/GetEntitlementSnapshot', 'getEntitlementSnapshot', 'billing-service', 'billing', 'BOUND', ['read']),
            ('ru.taska.b2b.v1.BillingService/ReadReferencedPlanVersion', 'readReferencedPlanVersion', 'billing-service', 'billing', 'NONE', ['read', 'catalog:read'])):
            self.edge('issue-service', audience, rpc, action, ['SERVICE'], scopes, purpose, mode)
        self.refresh()

    def probe(self, client, actor, tenant, project, **extra):
        claims = json.loads(base64.urlsafe_b64decode(client.token.split('.')[1] + '=='))
        libraries = self.material / 'issue-probe-libs'
        if not libraries.exists():
            libraries.mkdir(mode=0o700)
            with zipfile.ZipFile(self.dir / 'jars/issue-service.jar') as archive:
                for item in archive.infolist():
                    if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                        (libraries / item.filename.rsplit('/', 1)[1]).write_bytes(archive.read(item))
        parameters = self.material / 'issue-probe.json'
        e.private_json(parameters, {'material': str(self.material), 'actor': actor, 'tenant': tenant,
            'credential': claims['sid'], 'project': project,
            'authPort': int(self.compose('port', 'auth-service', '9090').strip().rsplit(':', 1)[1]),
            'issuePort': int(self.compose('port', 'issue-service', '9090').strip().rsplit(':', 1)[1]), **extra})
        output = e.run_command(['java', '--class-path', str(libraries / '*'),
            str(self.root / 'scripts/harness/IssueAclProbe.java'), str(parameters)], self.root, 180,
            diagnostic_path=self.dir / 'issue-probe-diagnostic.json')
        return json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{')))

    def scenarios(self):
        actor, clients = self.seed()
        for _, client in clients:
            client.timeout = 60
        projects = []
        for tenant, client in clients:
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            projects.append(client.expect('POST', '/api/v1/projects', 201,
                {'projectKey': 'ISS', 'name': 'Owned issue fixture'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id'])
        tenant, owner = clients[0]
        _, other = clients[1]
        project, foreign = projects
        # Проверяем готовность чтением до первой записи, не повторяя команды после тайм-аута.
        for client, identifier in ((owner, project), (other, foreign)):
            t.wait_protected_read(client, '/api/v1/projects/' + identifier + '/workflows', 200)
        workflow = self.workflows(owner, project)['items'][0]['workflow']
        # Публичное создание проверяет нативный контекст gateway, квоту и выбор реального workflow.
        foreign_issue = other.expect('POST', '/api/v1/projects/' + foreign + '/issues', 201,
            {'issueType': 'TASK', 'summary': 'Owned foreign fixture', 'description': 'Owned fixture', 'priority': 'MEDIUM'},
            {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
        source = next(s['id'] for s in workflow['statuses'] if s['statusKey'] == 'TODO')
        transition = next(x['id'] for x in workflow['transitions'] if x['fromStatusId'] == source)
        core = self.probe(owner, actor, tenant, project, foreignProject=foreign, foreignIssue=foreign_issue, transition=transition)
        issue = core.pop('issue')
        panel = owner.expect('GET', '/api/v1/issues/' + issue, 200).json()
        e.require(panel['issue']['version'] == 3, 'Public panel lost the native version')
        conflict = owner.expect('PATCH', '/api/v1/issues/' + issue, 412,
            {'summary': 'Denied stale version'}, {'If-Match': '1'}).json()
        e.require(conflict['code'] == 'VERSION_CONFLICT', 'Public stale-version problem lost its code')
        context = owner.expect('GET', '/api/v1/projects/' + project + '/context', 200).json()
        e.require(context['taskQuota']['used'] == 1 and context['taskQuota']['limit'] == 50, 'Project context invented task usage')
        stats = owner.expect('GET', '/api/v1/project-stats', 200).json()
        own = next(item for item in stats['items'] if item['projectId'] == project)
        e.require(own['taskQuota']['used'] == 1 and own['issueCount'] == 1, 'Project stats lost lifetime task quota')
        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','issue-member@harness.invalid','Owned viewer','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, member))
        self.sql('project_db', "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','VIEWER','%s',1);" % (tenant, project, member, actor))
        viewer, session = self.member_client(tenant, member, 1)
        denied = self.probe(viewer, member, tenant, project, mode='denyWrite', issue=issue)
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';" % member)
        viewer.expect('GET', '/api/v1/issues/' + issue, 403)
        claims = json.loads(base64.urlsafe_b64decode(viewer.token.split('.')[1] + '=='))
        self.sql('auth_db', "UPDATE taska.web_sessions SET assurance='ADMIN_EMAIL_MFA' WHERE id=(SELECT web_session_id FROM taska.tenant_sessions WHERE id='%s');" % claims['sid'])
        admin = self.probe(viewer, member, tenant, project, mode='adminWrite', issue=issue)
        e.require(int(self.sql('issue_db', "SELECT count(*) FROM taska.issue_authorization_audit WHERE tenant_id='%s' AND actor_id='%s' AND action='patchIssue';" % (tenant, member)).strip()) > 0,
                  'Administrative issue write was not audited')
        self.sql('auth_db', "UPDATE taska.users SET global_role='USER' WHERE id='%s';" % member)

        self.sql('project_db', "UPDATE taska.projects SET archived_at=now() WHERE tenant_id='%s' AND id='%s';" % (tenant, project))
        archived = self.probe(owner, actor, tenant, project, mode='denyWrite', issue=issue)
        self.sql('project_db', "UPDATE taska.projects SET archived_at=NULL WHERE tenant_id='%s' AND id='%s';" % (tenant, project))
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member))
        e.require(viewer.request('GET', '/api/v1/issues/by-key/ISS-1').status in (401, 403), 'Revoked member retained access')
        deleted = self.probe(owner, actor, tenant, project, mode='delete', issue=issue)
        # История и outbox содержат метаданные; пожизненное использование сохраняется после удаления.
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.issue_history WHERE issue_id='%s';" % issue).strip() == '5', 'History lost or duplicated')
        e.require(self.sql('issue_db', "SELECT lifetime_created FROM taska.project_counters WHERE tenant_id='%s' AND project_id='%s';" % (tenant, project)).strip() == '1', 'Delete reclaimed lifetime quota')
        self.sql('issue_db', "UPDATE taska.project_counters SET lifetime_created=50 WHERE tenant_id='%s' AND project_id='%s';" % (tenant, project))
        quota = owner.expect('POST', '/api/v1/projects/' + project + '/issues', 422,
            {'issueType': 'TASK', 'summary': 'Denied quota fixture', 'description': 'Owned fixture', 'priority': 'MEDIUM'},
            {'Idempotency-Key': str(uuid.uuid4())}).json()
        e.require(quota['code'] == 'QUOTA_EXCEEDED' and quota['quota'] == {'kind': 'TASK_LIFETIME', 'limit': 50, 'used': 50},
                  'Public quota problem lost authoritative usage')
        return {**core, **denied, **deleted, **admin, 'archivedWriteDenied': archived['aclReadAndWriteDenial'], 'revokedMembership': 'PASS', 'publicQuotaAndVersionProblems': 'PASS'}


def main():
    t.TrustedLocal = IssueAclLocal
    t.main()


if __name__ == '__main__':
    main()
