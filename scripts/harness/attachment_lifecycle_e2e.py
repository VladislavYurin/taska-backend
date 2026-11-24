"""TAS-367 owned native GET/Range/delete/restart with real credentials, ACL, ledger and S3."""
import http.client
import json
import time
import urllib.request
import uuid

import e2e as e
import trusted_rpc_e2e as t
from attachment_upload_e2e import AttachmentUploadLocal


class AttachmentLifecycleLocal(AttachmentUploadLocal):
    def object_probe(self, mode, keys=None):
        parameters = json.loads((self.material / 'attachment-s3.json').read_text())
        parameters.update(mode=mode, deletedKeys=keys or [])
        private = self.material / 'lifecycle-s3.json'
        e.private_json(private, parameters)
        e.run_command(['java', '--class-path', str(self.material / 'attachment-s3-libs/*'),
                       str(self.root / 'scripts/harness/AttachmentLifecycleS3Probe.java'), str(private)], self.root, 90,
                      diagnostic_path=self.dir / 'lifecycle-s3-diagnostic.json')

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        edges = [('issue-service', 'issue-service', 'ru.taska.issue.attachment.v1.AttachmentAdmissionService/',
                  [('ReconcileUploads', 'reconcileAttachmentUploads'), ('ReconcileDeletions', 'reconcileAttachmentDeletions')]),
                 ('issue-service', 'tenant-service', 'ru.taska.b2b.v1.QuotaAuthorityService/',
                  [('GetQuotaReservation', 'getQuotaReservation'), ('ReleaseStorageUsage', 'releaseStorageUsage'),
                   ('ReleaseQuota', 'releaseQuota'), ('CommitQuota', 'commitQuota')]),
                 ('tenant-service', 'issue-service', 'ru.taska.issue.attachment.v1.AttachmentAdmissionService/', [('CheckAdmission', 'checkAttachmentAdmission')]),
                 ('tenant-service', 'issue-service', 'ru.taska.b2b.v1.StorageQuotaOwnerService/',
                  [('Inspect', 'storageQuotaInspect'), ('Cancel', 'storageQuotaCancel'), ('Activate', 'storageQuotaActivate'), ('Manifests', 'storageQuotaManifests')])]
        for issuer, audience, prefix, methods in edges:
            for method, action in methods:
                self.edge(issuer, audience, prefix + method, action, ['SERVICE'], ['write', 'storage:recover'], 'STORAGE_CLEANUP')
                self.snapshot['edges'][-1]['serviceIdentities'] = ['issue-service']
        self.refresh()

    def positive(self, client, method, path, expected, body=None, headers=None):
        headers = dict(headers or {})
        headers.setdefault('X-Request-Id', str(uuid.uuid4()))
        for attempt in range(3):
            response = client.request(method, path, body, headers)
            if response.status == expected:
                return response
            e.require(response.status in (503, 504) and attempt < 2,
                      'Native lifecycle expected %s, got %s on %s' % (expected, response.status, path.rsplit('/', 1)[-1]))
            time.sleep(1)

    def upload_file(self, client, path, data):
        body = {'fileName': 'owned.txt', 'contentType': 'text/plain', 'sizeBytes': len(data)}
        upload = self.positive(client, 'POST', path + 'upload-url', 200, body, {'Idempotency-Key': str(uuid.uuid4())}).json()
        with urllib.request.urlopen(urllib.request.Request(upload['uploadUrl'], data=data, method='PUT', headers={'Content-Type': 'text/plain'}), timeout=30) as response:
            e.require(response.status // 100 == 2, 'Owned signed PUT failed')
        result = self.positive(client, 'POST', path + 'confirm', 201, {'objectKey': upload['objectKey'], 'fileName': 'owned.txt', 'contentType': 'text/plain'}).json()
        return result

    def scenarios(self):
        actor, clients = self.seed()
        self.object_probe('seed')
        fixtures = []
        for tenant, client in clients:
            client.timeout = 120
            project, issue = str(uuid.uuid4()), str(uuid.uuid4())
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('project_db', "INSERT INTO taska.projects(tenant_id,id,project_key,name,created_by) VALUES('%s','%s','LFC','Owned lifecycle','%s');" % (tenant, project, actor))
            self.sql('issue_db', "INSERT INTO taska.project_counters(tenant_id,project_id,next_issue_number,lifetime_created) VALUES('%s','%s',2,1);INSERT INTO taska.issues(tenant_id,id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id) VALUES('%s','%s','%s',1,'LFC-1','TASK','Owned lifecycle','TODO','MEDIUM','%s');" % (tenant, project, tenant, issue, project, actor))
            t.wait_protected_read(client, '/api/v1/projects/' + project, 200)
            t.wait_protected_read(client, '/api/v1/issues/' + issue, 200)
            fixtures.append((tenant, client, project, issue, '/api/v1/projects/' + project + '/issues/' + issue + '/attachments/'))
        tenant, owner, project, issue, path = fixtures[0]
        other_tenant, other, other_project, other_issue, other_path = fixtures[1]
        data = b'0123456789' * 2000000
        file = self.upload_file(owner, path, data)
        foreign = self.upload_file(other, other_path, b'foreign-owned')
        new_file = self.upload_file(owner, path, b'new-owned')
        file_path = path + file['id']
        content = self.positive(owner, 'GET', file_path + '/download-url', 200).json()['downloadUrl']
        e.require(content == file_path + '/content', 'Native download exposed a non-gateway capability')
        e.require(len(owner.expect('GET', path[:-1], 200).json()['items']) == 2, 'Confirmed list mismatch')
        response = owner.expect('GET', content, 206, headers={'Range': 'bytes=3-8'})
        e.require(response.body == data[3:9] and response.headers.get('Content-Range') == 'bytes 3-8/20000000', 'Range bytes differ')
        e.require('no-store' in response.headers.get('Cache-Control', ''), 'Download can be cached publicly')
        owner.expect('HEAD', content, 200)
        owner.expect('GET', content, 416, headers={'Range': 'bytes=0-1,3-4'})
        other.expect('GET', file_path + '/download-url', 404)
        owner.expect('GET', other_path + file['id'] + '/download-url', 404)
        owner.expect('GET', path + foreign['id'] + '/download-url', 404)

        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','lifecycle-member@harness.invalid','Owned member','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, member))
        self.sql('project_db', "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','VIEWER','%s',1);" % (tenant, project, member, actor))
        viewer, _ = self.member_client(tenant, member, 1)
        viewer.timeout = 120
        viewer.expect('GET', file_path + '/download-url', 200)
        viewer.expect('DELETE', file_path, 403)
        # A live HTTP response consumes one small prefix before the real primary membership revoke.
        request = urllib.request.Request(owner.origin + content, headers={'Authorization': 'Bearer ' + viewer.token})
        received = 0
        with urllib.request.urlopen(request, timeout=120) as stream:
            prefix = stream.read(1024)
            e.require(prefix == data[:len(prefix)], 'Initial stream bytes differ')
            received += len(prefix)
            self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member))
            try:
                while True:
                    block = stream.read(65536)
                    if not block:
                        break
                    e.require(block == data[received:received + len(block)], 'Stream contains unexpected bytes or appended error JSON')
                    received += len(block)
            except http.client.IncompleteRead as failure:
                e.require(failure.partial == data[received:received + len(failure.partial)], 'Final stream bytes differ')
                received += len(failure.partial)
        e.require(received < len(data), 'Revoked member received the entire remaining stream')
        e.require(viewer.request('GET', content, headers={'Range': 'bytes=0-1'}).status in (401, 403), 'Revoked member retained Range access')

        # New typed configuration versions, only in this owned database.
        def flag(value):
            self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT 'ATTACHMENT_UPLOAD',max(version)+1,'BOOLEAN','%s'::jsonb,now(),'%s',now() FROM taska.platform_configuration_versions WHERE key='ATTACHMENT_UPLOAD';" % (str(value).lower(), actor))
        flag(False)
        owner.expect('GET', content, 206, headers={'Range': 'bytes=-3'})
        flag(True)
        restriction = str(uuid.uuid4())
        self.sql('tenant_db', "INSERT INTO taska.tenant_restrictions(tenant_id,kind,source_id,active,version,reason_code,created_by,created_at) VALUES('%s','QUOTA_EXCEEDED','%s',true,1,'STORAGE_BYTES_EXCEEDED','%s',now());" % (tenant, restriction, actor))
        owner.expect('DELETE', file_path, 403)
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.attachment_deletion_jobs;").strip() == '0', 'Denied delete persisted a job')
        self.sql('tenant_db', "UPDATE taska.tenant_restrictions SET active=false,version=version+1,resolved_at=now() WHERE tenant_id='%s' AND source_id='%s';" % (tenant, restriction))
        deleter = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','lifecycle-deleter@harness.invalid','Owned deleter','ACTIVE','USER');" % deleter)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, deleter))
        self.sql('project_db', "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','MEMBER','%s',1);" % (tenant, project, deleter, actor))
        deleting_member, _ = self.member_client(tenant, deleter, 1)
        deleting_member.timeout = 120
        # Scheduler starts with its exact owned tenant worklist; no global scan.
        props = self.material / 'issue-service/application.properties'
        props.write_text(props.read_text() + '\ntaska.attachments.cleanup-tenants=' + tenant + '\ntaska.attachments.cleanup-interval=2s\n')
        self.compose('restart', 'issue-service', timeout=120)
        self.wait_ready('http://' + self.compose('port', 'issue-service', '8080').strip())
        t.wait_protected_read(owner, '/api/v1/issues/' + issue, 200)
        deleting_member.expect('DELETE', file_path, 204)
        owner.expect('DELETE', file_path, 204)
        owner.expect('GET', content, 404)
        e.require(len(owner.expect('GET', path[:-1], 200).json()['items']) == 1, 'Pending deletion remained visible')
        e.require(self.sql('tenant_db', "SELECT used FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % tenant).strip() == str(len(data) + 9), 'Delete released bytes without S3 proof')

        # Keep the real 300s admission guard. Stop only this run's S3 before the first eligible purge.
        self.compose('stop', 'storage', timeout=60)
        deadline = time.monotonic() + 390
        while time.monotonic() < deadline:
            remaining = float(self.sql('issue_db', "SELECT greatest(0,extract(epoch FROM (put_not_after-now()))) FROM taska.attachment_uploads WHERE id='%s';" % file['id']).strip())
            attempts = int(self.sql('issue_db', "SELECT attempts FROM taska.attachment_deletion_jobs WHERE attachment_id='%s';" % file['id']).strip())
            code = self.sql('issue_db', "SELECT coalesce(last_code,'') FROM taska.attachment_deletion_jobs WHERE attachment_id='%s';" % file['id']).strip()
            e.require(not code or code in ('PURGE_PUT_WINDOW_STILL_OPEN', 'PURGE_UNKNOWN', 'PURGE_UNAVAILABLE'), 'Unexpected cleanup refusal: ' + code)
            if remaining == 0:
                before = attempts
                break
            time.sleep(min(10, max(1, remaining)))
        else:
            raise e.Failure('Owned PUT window did not expire')
        deadline = time.monotonic() + 80
        while time.monotonic() < deadline:
            state = self.sql('issue_db', "SELECT attempts||'/'||coalesce(last_code,'') FROM taska.attachment_deletion_jobs WHERE attachment_id='%s';" % file['id']).strip().split('/')
            if int(state[0]) > before and state[1] in ('PURGE_UNKNOWN', 'PURGE_UNAVAILABLE'):
                break
            time.sleep(2)
        else:
            raise e.Failure('S3 failure was not durably retried after PUT expiry')
        e.require(self.sql('issue_db', "SELECT state FROM taska.attachment_object_manifest WHERE id='%s';" % file['id']).strip() == 'DELETE_PENDING', 'Failed S3 purge lost pending state')
        # Revoke the original uploader too: accepted technical continuation uses fresh SERVICE authority.
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id IN ('%s','%s');" % (tenant, actor, deleter))
        self.compose('restart', 'issue-service', timeout=120)
        self.compose('start', 'storage', timeout=60)
        self.wait_ready('http://' + self.compose('port', 'issue-service', '8080').strip())
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            if self.sql('issue_db', "SELECT state FROM taska.attachment_deletion_jobs WHERE attachment_id='%s';" % file['id']).strip() == 'COMPLETED':
                break
            time.sleep(2)
        else:
            code = self.sql('issue_db', "SELECT coalesce(last_code,'') FROM taska.attachment_deletion_jobs WHERE attachment_id='%s';" % file['id']).strip()
            raise e.Failure('Fresh SERVICE worker did not complete retained deletion: ' + code)
        e.require(self.sql('tenant_db', "SELECT used FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % tenant).strip() == '9', 'Release usage amount differs')
        e.require(self.sql('tenant_db', "SELECT count(*) FROM taska.quota_effects WHERE tenant_id='%s' AND resource='STORAGE_BYTES' AND effect_kind='RELEASE_USAGE';" % tenant).strip() == '1', 'Release usage duplicated')
        keys = self.sql('issue_db', "SELECT object_key||'|'||staging_object_key FROM taska.attachment_object_manifest WHERE id='%s';" % file['id']).strip().split('|')
        self.object_probe('verify', keys)
        e.require(other.expect('GET', other_path + foreign['id'] + '/content', 200).body == b'foreign-owned', 'Cleanup changed another tenant object')
        # Re-establish an owned current member session; old credentials remain revoked.
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='ACTIVE',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member))
        current, _ = self.member_client(tenant, member, 3)
        e.require(current.expect('GET', path + new_file['id'] + '/content', 200).body == b'new-owned', 'Late cleanup changed a new attachment')
        return {'authorizedRangeHead': 'PASS', 'liveStreamRevocation': 'PASS', 'uploadFlagOffRead': 'PASS',
                'readOnlyDeleteStatePreserved': 'PASS', 'tenantPathIsolation': 'PASS', 'durableS3FailureRestart': 'PASS',
                'serviceContinuationAfterUploaderRevoke': 'PASS', 'deleteByOtherMember': 'PASS', 'releaseUsageOnce': 'PASS',
                'lateCleanupNewObjectIsolation': 'PASS', 'avatarNamespacePreserved': 'PASS', 'physicalOwnedVersionsAbsent': 'PASS', 'realPutWindow': 'PASS'}


if __name__ == '__main__':
    t.main(scenario='attachment-lifecycle', local_cls=AttachmentLifecycleLocal)
