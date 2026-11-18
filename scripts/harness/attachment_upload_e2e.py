"""TAS-365: собственные REST/mTLS/PostgreSQL/S3 fixtures, без общей среды."""
import json
import urllib.request
import uuid
import zipfile

import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal


class AttachmentUploadLocal(IssueAclLocal):
    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        kinds, scopes = ['WEB_TENANT', 'USER_PAT'], ['read', 'write']
        for method, action in (('ReserveQuota', 'reserveQuota'), ('GetQuotaReservation', 'getQuotaReservation'),
                               ('CommitQuota', 'commitQuota'), ('ReleaseQuota', 'releaseQuota')):
            self.edge('issue-service', 'tenant-service', 'ru.taska.b2b.v1.QuotaAuthorityService/' + method, action, kinds, scopes)
        for method, action in (('Inspect', 'storageQuotaInspect'), ('Cancel', 'storageQuotaCancel'),
                               ('Activate', 'storageQuotaActivate'), ('Manifests', 'storageQuotaManifests')):
            self.edge('tenant-service', 'issue-service', 'ru.taska.b2b.v1.StorageQuotaOwnerService/' + method, action, kinds, scopes)
        self.edge('tenant-service', 'issue-service', 'ru.taska.issue.attachment.v1.AttachmentAdmissionService/CheckAdmission',
                  'checkAttachmentAdmission', kinds, scopes)
        self.refresh()
        # Дополнительный port/storage config относится только к собственному Compose.
        compose = json.loads(self.compose_file.read_text())
        compose['services']['storage']['ports'] = ['127.0.0.1::9000']
        e.private_json(self.compose_file, compose)
        self.compose('up', '-d', '--no-deps', 'storage', timeout=180)
        endpoint = 'http://' + self.compose('port', 'storage', '9000').strip()
        properties = self.material / 'issue-service/application.properties'
        properties.write_text(properties.read_text() + 'storage.public-url=' + endpoint + '\n')
        libraries = self.material / 'attachment-s3-libs'
        libraries.mkdir(mode=0o700)
        with zipfile.ZipFile(self.dir / 'jars/issue-service.jar') as archive:
            for item in archive.infolist():
                if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                    (libraries / item.filename.rsplit('/', 1)[1]).write_bytes(archive.read(item))
        parameters = self.material / 'attachment-s3.json'
        e.private_json(parameters, {'endpoint': endpoint, 'bucket': 'e2e-attachments',
                                  'access': 'e2e-only-access', 'secret': 'e2e-only-secret'})
        e.run_command(['java', '--class-path', str(libraries / '*'),
                       str(self.root / 'scripts/harness/AttachmentS3Fixture.java'), str(parameters)], self.root, 90,
                      diagnostic_path=self.dir / 'attachment-s3-diagnostic.json')

    def scenarios(self):
        actor, clients = self.seed()
        fixtures = []
        for tenant, client in clients:
            client.timeout = 60
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            project = client.expect('POST', '/api/v1/projects', 201,
                                    {'projectKey': 'UPL', 'name': 'Owned attachment fixture'},
                                    {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
            self.ready_workflow(client, project)
            issue = client.expect('POST', '/api/v1/projects/' + project + '/issues', 201,
                                  {'issueType': 'TASK', 'summary': 'Owned attachment issue', 'priority': 'MEDIUM'},
                                  {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
            fixtures.append((tenant, client, project, issue))
        tenant, owner, project, issue = fixtures[0]
        _, foreign, foreign_project, foreign_issue = fixtures[1]
        path = '/api/v1/projects/' + project + '/issues/' + issue + '/attachments/'
        payload = {'fileName': 'owned.txt', 'contentType': 'text/plain', 'sizeBytes': 10}
        key = str(uuid.uuid4())
        uploaded = owner.expect('POST', path + 'upload-url', 200, payload, {'Idempotency-Key': key}).json()
        repeated = owner.expect('POST', path + 'upload-url', 200, payload, {'Idempotency-Key': key}).json()
        e.require(uploaded['objectKey'] == repeated['objectKey'], 'Upload retry allocated another resource')
        # URL не попадает в report/state/log; это только собственная capability.
        request = urllib.request.Request(uploaded['uploadUrl'], data=b'0123456789', method='PUT',
                                         headers={'Content-Type': 'text/plain'})
        with urllib.request.urlopen(request, timeout=20) as response:
            e.require(response.status // 100 == 2, 'Owned signed PUT failed')
        confirm = {'objectKey': uploaded['objectKey'], 'fileName': 'owned.txt', 'contentType': 'text/plain'}
        result = owner.expect('POST', path + 'confirm', 201, confirm).json()
        replay = owner.expect('POST', path + 'confirm', 201, confirm).json()
        e.require(result == replay and result['sizeBytes'] == 10, 'Confirm replay changed metadata')
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.issue_attachments WHERE tenant_id='%s';" % tenant).strip() == '1', 'Attachment duplicated')
        e.require(self.sql('tenant_db', "SELECT count(*) FROM taska.quota_effects WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % tenant).strip() == '1', 'Byte effect duplicated')
        foreign_path = '/api/v1/projects/' + foreign_project + '/issues/' + foreign_issue + '/attachments/'
        foreign.expect('POST', foreign_path + 'confirm', 404, confirm)
        owner.expect('POST', '/api/v1/projects/' + foreign_project + '/issues/' + issue + '/attachments/confirm', 404, confirm)
        owner.expect('POST', path + 'upload-url', 409, dict(payload, sizeBytes=11), {'Idempotency-Key': key})
        # Дополнительный собственный intent для проверки отзыва membership.
        late = owner.expect('POST', path + 'upload-url', 200, payload, {'Idempotency-Key': str(uuid.uuid4())}).json()
        # Отзыв реального membership закрывает даже тёплый confirm без нового расхода.
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, actor))
        response = owner.request('POST', path + 'confirm', body=dict(confirm, objectKey=late['objectKey']))
        e.require(response.status in (401, 403), 'Revoked membership retained confirm access')
        return {'publicRestSignedPutConfirm': 'PASS', 'tenantAndProjectIsolation': 'PASS',
                'retryAndAccountingOnce': 'PASS', 'payloadConflict': 'PASS', 'membershipRevocation': 'PASS'}


def main():
    t.TrustedLocal = AttachmentUploadLocal
    t.main()


if __name__ == '__main__':
    main()
