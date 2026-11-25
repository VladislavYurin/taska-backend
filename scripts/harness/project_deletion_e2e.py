"""TAS-459: собственный REST/mTLS/PostgreSQL/S3 прогон удаления проекта и повторов."""
import json
import time
import uuid
import urllib.request

import e2e as e
import trusted_rpc_e2e as t
from attachment_lifecycle_e2e import AttachmentLifecycleLocal


class ProjectDeletionLocal(AttachmentLifecycleLocal):
    def __init__(self, root, store, state):
        super().__init__(root, store, state)
        self.cleanup_tenants = [str(uuid.uuid4()), str(uuid.uuid4())]

    def seed(self):
        return super().seed(self.cleanup_tenants)

    def configure_service(self, name, env, props):
        super().configure_service(name, env, props)
        if name == 'project-service':
            props['taska.projects.deletion-tenants'] = ','.join(self.cleanup_tenants)
            props['taska.projects.recovery-tenants'] = ''
            props['taska.projects.deletion-interval'] = '2s'

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        kinds, scopes = ['WEB_TENANT', 'USER_PAT'], ['read', 'write']
        for method, action in [('DeleteProject', 'deleteProject'), ('GetDeletion', 'getProjectDeletion')]:
            self.edge('api-gateway', 'project-service', 'ru.taska.b2b.v1.ProjectDeletionService/' + method, action, kinds, scopes)
        edges = [('project-service', 'project-service', 'ProjectDeletionService', 'RecoverDeletion', 'recoverProjectDeletion'),
                 ('project-service', 'issue-service', 'ProjectResourceCleanupService', 'CleanupProject', 'cleanupProjectResources'),
                 ('project-service', 'workflow-service', 'ProjectResourceCleanupService', 'CleanupProject', 'cleanupProjectResources'),
                 ('issue-service', 'project-service', 'ProjectDeletionService', 'InspectDeletion', 'inspectProjectDeletion'),
                 ('workflow-service', 'project-service', 'ProjectDeletionService', 'InspectDeletion', 'inspectProjectDeletion'),
                 ('project-service', 'tenant-service', 'QuotaAuthorityService', 'GetQuotaReservation', 'getQuotaReservation'),
                 ('project-service', 'tenant-service', 'QuotaAuthorityService', 'ReleaseProjectUsage', 'releaseProjectUsage'),
                 ('tenant-service', 'project-service', 'ProjectQuotaOwnerService', 'Inspect', 'projectQuotaInspect'),
                 ('tenant-service', 'project-service', 'ProjectQuotaOwnerService', 'Inventory', 'projectQuotaInventory')]
        for issuer, audience, service, method, action in edges:
            self.edge(issuer, audience, 'ru.taska.b2b.v1.' + service + '/' + method, action,
                      ['SERVICE'], ['write', 'project:delete'], 'PROJECT_CLEANUP')
            self.snapshot['edges'][-1]['serviceIdentities'] = ['project-service']
        self.refresh()

    def operation(self, owner, tenant, operation):
        return owner.expect('GET', '/api/v1/tenants/' + tenant + '/operations/' + operation, 200).json()

    def exhaust_free_tasks(self, client, project):
        url = '/api/v1/projects/' + project + '/issues'
        body = {'issueType': 'TASK', 'summary': 'Owned FREE lifetime fixture',
                'priority': 'MEDIUM', 'description': 'Owned fixture'}
        for _ in range(49):
            self.positive(client, 'POST', url, 201, body, {'Idempotency-Key': str(uuid.uuid4())})
        refused = client.expect('POST', url, 400, body, {'Idempotency-Key': str(uuid.uuid4())}).json()
        e.require(refused.get('details', {}).get('code') == 'QUOTA_EXCEEDED', 'FREE project accepted more than 50 lifetime tasks')

    def scenarios(self):
        initial = e.ASSERTIONS
        actor, clients = self.seed()
        self.object_probe('seed')
        fixtures = []
        for tenant, client in clients:
            client.timeout = 60
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            project = self.positive(client, 'POST', '/api/v1/projects', 201, {'projectKey': 'DEL', 'name': 'Owned deletion fixture'}, {'Idempotency-Key': str(uuid.uuid4())}).json()
            self.ready_workflow(client, project['id'])
            issue = self.positive(client, 'POST', '/api/v1/projects/' + project['id'] + '/issues', 201,
                {'issueType': 'TASK', 'summary': 'Owned deletion issue', 'priority': 'MEDIUM', 'description': 'Owned fixture'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
            path = '/api/v1/projects/' + project['id'] + '/issues/' + issue + '/attachments/'
            attachment = self.upload_file(client, path, b'owned-project-delete')
            fixtures.append((tenant, client, project, issue, path, attachment))
        tenant, owner, project, issue, path, attachment = fixtures[0]
        other_tenant, other, foreign, foreign_issue, foreign_path, foreign_attachment = fixtures[1]
        project_id = project['id']
        abandoned_key = str(uuid.uuid4())
        abandoned_data = b'owned-unconfirmed'
        abandoned = self.positive(owner, 'POST', path + 'upload-url', 200,
            {'fileName': 'unconfirmed.txt', 'contentType': 'text/plain', 'sizeBytes': len(abandoned_data)},
            {'Idempotency-Key': abandoned_key}).json()
        with urllib.request.urlopen(urllib.request.Request(abandoned['uploadUrl'], data=abandoned_data,
                method='PUT', headers={'Content-Type': 'text/plain'}), timeout=30) as response:
            e.require(response.status // 100 == 2, 'Owned unconfirmed PUT failed')
        abandoned_id = self.sql('issue_db', "SELECT id FROM taska.attachment_uploads WHERE tenant_id='%s' AND request_key='%s';" % (tenant, abandoned_key)).strip()
        self.exhaust_free_tasks(owner, project_id)
        url = '/api/v1/tenants/' + tenant + '/projects/' + project_id + '/deletion'
        payload = {'confirmed': True, 'confirmedProjectKey': 'DEL'}
        key = str(uuid.uuid4())
        headers = {'Idempotency-Key': key, 'If-Match': '"%s"' % project['version']}
        before = self.sql('project_db', 'SELECT count(*) FROM taska.project_deletion_operations;').strip()
        owner.expect('POST', url, 428, payload, {'Idempotency-Key': key})
        owner.expect('POST', url, 412, payload, dict(headers, **{'If-Match': '"999"'}))
        owner.expect('POST', url, 422, dict(payload, confirmedProjectKey='WRONG'), headers)
        other.expect('POST', url, 403, payload, headers)
        owner.expect('POST', '/api/v1/tenants/' + tenant + '/projects/' + foreign['id'] + '/deletion', 404, payload, headers)
        e.require(self.sql('project_db', 'SELECT count(*) FROM taska.project_deletion_operations;').strip() == before, 'Denied deletion created an intent')
        keys = json.loads(self.sql('issue_db', "SELECT jsonb_agg(k)::text FROM taska.attachment_object_manifest m CROSS JOIN LATERAL (VALUES(m.object_key),(m.staging_object_key)) owned(k) WHERE m.tenant_id='%s' AND m.project_id='%s';" % (tenant, project_id)).strip())
        self.compose('pause', 'storage', timeout=60)
        operation = owner.expect('POST', url, 202, payload, headers).json()
        op = operation['id']
        e.require(operation['type'] == 'PROJECT_DELETE' and operation['resourceId'] == project_id, 'Deletion operation scope differs')
        self.operation(owner, tenant, op)
        owner.expect('POST', url, 202, payload, headers)
        owner.expect('POST', url, 409, payload, dict(headers, **{'Idempotency-Key': str(uuid.uuid4())}))
        owner.expect('POST', '/api/v1/projects/' + project_id + '/issues', 404,
                     {'issueType': 'TASK', 'summary': 'Late creation', 'priority': 'MEDIUM', 'description': 'Owned fixture'}, {'Idempotency-Key': str(uuid.uuid4())})
        e.require(self.sql('tenant_db', "SELECT used FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '1', 'Unknown cleanup released project usage')
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            expired = self.sql('issue_db', "SELECT state FROM taska.attachment_object_manifest WHERE tenant_id='%s' AND id='%s';" % (tenant, abandoned_id)).strip()
            if expired == 'EXPIRED':
                break
            time.sleep(2)
        else:
            raise e.Failure('Unconfirmed upload did not record cancellation intent')
        e.require(self.sql('issue_db', "SELECT project_cleanup_complete FROM taska.attachment_uploads WHERE tenant_id='%s' AND id='%s';" % (tenant, abandoned_id)).strip() == 'f', 'EXPIRED was mistaken for cleanup ACK')
        e.require(int(self.sql('tenant_db', "SELECT reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % tenant).strip()) > 0, 'Failed purge released the storage reservation')
        # Запрещаем только финальный локальный ACK в собственной fixture: root release должен остаться однократным.
        self.sql('project_db', "ALTER TABLE taska.project_deletion_operations ADD CONSTRAINT owned_lost_ack CHECK(id<>'%s' OR state<>'SUCCEEDED');" % op)
        self.compose('restart', 'project-service', timeout=120)
        self.wait_ready('http://' + self.compose('port', 'project-service', '8080').strip())
        self.compose('unpause', 'storage', timeout=60)
        deadline = time.monotonic() + 420
        while time.monotonic() < deadline:
            state = self.sql('project_db', "SELECT state FROM taska.project_deletion_operations WHERE tenant_id='%s' AND id='%s';" % (tenant, op)).strip()
            used = self.sql('tenant_db', "SELECT used FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip()
            if state == 'DELETED' and used == '0':
                break
            time.sleep(5)
        else:
            raise e.Failure('Project cleanup or primary quota release did not complete')
        e.require(self.sql('issue_db', "SELECT retired_at IS NOT NULL FROM taska.project_counters WHERE tenant_id='%s' AND project_id='%s';" % (tenant, project_id)).strip() == 't', 'Lifetime tombstone missing')
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.issues WHERE tenant_id='%s' AND project_id='%s';" % (tenant, project_id)).strip() == '0', 'Issue business rows retained')
        e.require(self.sql('workflow_db', "SELECT count(*) FROM taska.workflows WHERE tenant_id='%s' AND owner_project_id='%s';" % (tenant, project_id)).strip() == '0', 'Project workflow rows retained')
        e.require(self.sql('workflow_db', "SELECT count(*) FROM taska.tenant_workflow_templates WHERE tenant_id='%s';" % tenant).strip() == '1', 'Shared company template was removed')
        self.object_probe('verify', keys)
        self.sql('project_db', 'ALTER TABLE taska.project_deletion_operations DROP CONSTRAINT owned_lost_ack;')
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if self.operation(owner, tenant, op)['state'] == 'SUCCEEDED':
                break
            time.sleep(2)
        else:
            raise e.Failure('Lost final ACK did not recover')
        owner.expect('POST', url, 202, payload, headers)
        self.positive(other, 'GET', foreign_path + foreign_attachment['id'] + '/download-url', 200)
        other.expect('GET', '/api/v1/issues/' + foreign_issue, 200)
        e.require(self.sql('tenant_db', "SELECT used FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '0', 'Replay changed released usage')
        new_project = self.positive(owner, 'POST', '/api/v1/projects', 201, {'projectKey': 'NEW', 'name': 'Owned replacement'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
        e.require(new_project != project_id, 'New project reused deleted UUID')
        owner.expect('POST', '/api/v1/projects/' + new_project + '/issues', 201,
                     {'issueType': 'TASK', 'summary': 'New lifetime', 'priority': 'MEDIUM', 'description': 'Owned fixture'}, {'Idempotency-Key': str(uuid.uuid4())})
        e.require(self.sql('issue_db', "SELECT lifetime_created FROM taska.project_counters WHERE tenant_id='%s' AND project_id='%s';" % (tenant, new_project)).strip() == '1', 'New project inherited old lifetime usage')
        self.exhaust_free_tasks(owner, new_project)
        return {'scenario': 'project-deletion', 'admissionAndRefusals': 'PASS', 'durableReplay': 'PASS',
                'crashAndLostAck': 'PASS', 'physicalResourceCleanup': 'PASS', 'expiredIsNotCleanupAck': 'PASS', 'quotaRelease': 'PASS',
                'tenantAndAvatarIsolation': 'PASS', 'lifetimeTombstone': 'PASS', 'newProjectCounter': 'PASS', 'oldAndNewFreeLimit50': 'PASS',
                'testCount': e.ASSERTIONS - initial}


def main():
    t.main(local_cls=ProjectDeletionLocal, description=__doc__, scenario='project-deletion')


if __name__ == '__main__':
    main()
