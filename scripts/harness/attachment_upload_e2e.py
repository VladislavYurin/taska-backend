"""TAS-365: собственные REST/mTLS/PostgreSQL/S3 fixtures, без общей среды."""
import json
from concurrent.futures import ThreadPoolExecutor
import time
import threading
import urllib.request
import uuid
import zipfile

import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal


class AttachmentUploadLocal(IssueAclLocal):
    def compose(self, *args, timeout=600, input_text=None):
        return e.run_command(['docker', 'compose', '--env-file', '/dev/null', '-p', self.project,
                              '-f', str(self.compose_file), *args], self.root, timeout, input_text,
                             diagnostic_path=self.dir / 'attachment-compose-diagnostic.json')

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
        compose['services']['issue-service']['environment']['STORAGE_PUBLIC_URL'] = endpoint
        e.private_json(self.compose_file, compose)
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
        transients, counter_lock = {'upload': 0, 'confirm': 0}, threading.Lock()
        readiness_transients = {'503': 0, '504': 0}

        def positive(client, path, expected, body, headers=None):
            # Проверяем bounded retry того же intent, а не создаём новую команду после тайм-аута.
            headers = dict(headers or {})
            headers.setdefault('X-Request-Id', str(uuid.uuid4()))
            for attempt in range(3):
                response = client.request('POST', path, body=body, headers=headers)
                if response.status == expected:
                    return response.json()
                e.require(response.status == 503 and attempt < 2,
                          'Upload intent did not recover: expected %s, got %s' % (expected, response.status))
                with counter_lock:
                    transients['confirm' if path.endswith('/confirm') else 'upload'] += 1
                time.sleep(0.2 * (attempt + 1))

        fixtures = []
        for tenant, client in clients:
            client.timeout = 60
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            # Существующий проект и issue — собственные fixtures; создание workflow не входит в upload scope.
            project, issue = str(uuid.uuid4()), str(uuid.uuid4())
            self.sql('project_db', "INSERT INTO taska.projects(tenant_id,id,project_key,name,created_by) VALUES('%s','%s','UPL','Owned attachment fixture','%s');" % (tenant, project, actor))
            self.sql('issue_db', "INSERT INTO taska.project_counters(tenant_id,project_id,next_issue_number,lifetime_created) VALUES('%s','%s',2,1);INSERT INTO taska.issues(tenant_id,id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id) VALUES('%s','%s','%s',1,'UPL-1','TASK','Owned attachment issue','TODO','MEDIUM','%s');" % (tenant, project, tenant, issue, project, actor))
            deadline = time.monotonic() + 180
            probe_path = '/api/v1/projects/' + project
            while time.monotonic() < deadline:
                response = client.request('GET', probe_path)
                if response.status == 200:
                    break
                e.require(response.status in (503, 504), 'Protected readiness returned unexpected status %s' % response.status)
                readiness_transients[str(response.status)] += 1
                time.sleep(1)
            else:
                raise e.Blocked('Attachment primary readiness deadline exceeded')
            # Прогреваем только чтение issue и его primary facts до первой команды upload.
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                response = client.request('GET', '/api/v1/issues/' + issue)
                if response.status == 200:
                    break
                e.require(response.status in (503, 504), 'Issue readiness returned unexpected status %s' % response.status)
                readiness_transients[str(response.status)] += 1
                time.sleep(1)
            else:
                raise e.Blocked('Attachment issue readiness deadline exceeded')
            fixtures.append((tenant, client, project, issue))
        tenant, owner, project, issue = fixtures[0]
        _, foreign, foreign_project, foreign_issue = fixtures[1]
        path = '/api/v1/projects/' + project + '/issues/' + issue + '/attachments/'
        payload = {'fileName': 'owned.txt', 'contentType': 'text/plain', 'sizeBytes': 10}
        owner.expect('POST', path + 'upload-url', 400, dict(payload, contentType='application/x-msdownload'),
                     {'Idempotency-Key': str(uuid.uuid4())})
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.attachment_uploads WHERE tenant_id='%s';" % tenant).strip() == '0', 'Rejected MIME persisted an intent')
        key = str(uuid.uuid4())
        uploaded = positive(owner, path + 'upload-url', 200, payload, {'Idempotency-Key': key})
        repeated = positive(owner, path + 'upload-url', 200, payload, {'Idempotency-Key': key})
        e.require(uploaded['objectKey'] == repeated['objectKey'], 'Upload retry allocated another resource')
        # URL не попадает в report/state/log; это только собственная capability.
        request = urllib.request.Request(uploaded['uploadUrl'], data=b'0123456789', method='PUT',
                                         headers={'Content-Type': 'text/plain'})
        with urllib.request.urlopen(request, timeout=20) as response:
            e.require(response.status // 100 == 2, 'Owned signed PUT failed')
        confirm = {'objectKey': uploaded['objectKey'], 'fileName': 'owned.txt', 'contentType': 'text/plain'}
        alternate_actor = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','alternate-upload@harness.invalid','Alternate owner','ACTIVE','USER');" % alternate_actor)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, alternate_actor))
        self.sql('project_db', "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','ADMIN','%s',1);" % (tenant, project, alternate_actor, actor))
        alternate, _ = self.member_client(tenant, alternate_actor, 1)
        alternate.timeout = 60
        alternate.expect('GET', '/api/v1/issues/' + issue, 200)
        alternate.expect('POST', path + 'confirm', 403, confirm)
        e.require(self.sql('issue_db', "SELECT NOT confirm_requested FROM taska.attachment_uploads WHERE tenant_id='%s' AND actor_id='%s' AND request_key='%s';" % (tenant, actor, key)).strip() == 't', 'Foreign actor changed confirm intent')

        with ThreadPoolExecutor(max_workers=3) as workers:
            confirmations = list(workers.map(lambda _: positive(owner, path + 'confirm', 201, confirm), range(3)))
        result = confirmations[0]
        e.require(all(item == result for item in confirmations), 'Concurrent confirm changed metadata')
        replay = positive(owner, path + 'confirm', 201, confirm)
        e.require(result == replay and result['sizeBytes'] == 10, 'Confirm replay changed metadata')
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.issue_attachments WHERE tenant_id='%s';" % tenant).strip() == '1', 'Attachment duplicated')
        e.require(self.sql('tenant_db', "SELECT count(*) FROM taska.quota_effects WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % tenant).strip() == '1', 'Byte effect duplicated')
        foreign_path = '/api/v1/projects/' + foreign_project + '/issues/' + foreign_issue + '/attachments/'
        foreign.expect('POST', foreign_path + 'confirm', 404, confirm)
        owner.expect('POST', '/api/v1/projects/' + foreign_project + '/issues/' + issue + '/attachments/confirm', 404, confirm)
        owner.expect('POST', foreign_path + 'confirm', 404, confirm)
        owner.expect('POST', path + 'upload-url', 409, dict(payload, sizeBytes=11), {'Idempotency-Key': key})
        # Дополнительный собственный intent для проверки отзыва membership.
        fallback_key = str(uuid.uuid4())
        late = positive(owner, path + 'upload-url', 200, payload, {'X-Request-Id': fallback_key})
        fallback_replay = positive(owner, path + 'upload-url', 200, payload, {'X-Request-Id': fallback_key})
        e.require(late['objectKey'] == fallback_replay['objectKey'], 'Request ID fallback lost idempotency')
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.attachment_uploads WHERE tenant_id='%s' AND request_key='%s';" % (tenant, fallback_key)).strip() == '1', 'Request ID fallback was not persisted')
        # Заполняем квоту разрешёнными reservations без больших объектов и подмены counters.
        for size in [20000000] * 4 + [19999980]:
            positive(owner, path + 'upload-url', 200, dict(payload, sizeBytes=size),
                     {'Idempotency-Key': str(uuid.uuid4())})
        quota_state = self.sql('tenant_db', "SELECT used||'/'||reserved||'/'||(SELECT count(*) FROM taska.quota_reservations WHERE tenant_id='%s' AND resource='STORAGE_BYTES') FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % (tenant, tenant)).strip()
        quota_problem = positive(owner, path + 'upload-url', 422, payload, {'Idempotency-Key': str(uuid.uuid4())})
        e.require(quota_problem['code'] == 'QUOTA_EXCEEDED'
                  and quota_problem['details']['quota.kind'] == 'STORAGE_BYTES', 'Storage quota problem lost its machine code')
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved||'/'||(SELECT count(*) FROM taska.quota_reservations WHERE tenant_id='%s' AND resource='STORAGE_BYTES') FROM taska.quota_counters WHERE tenant_id='%s' AND resource='STORAGE_BYTES';" % (tenant, tenant)).strip() == quota_state, 'Quota refusal changed accounting or allocated a reservation')
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';UPDATE taska.web_sessions SET assurance='ADMIN_EMAIL_MFA' WHERE user_id='%s';" % (actor, actor))
        administrative = positive(owner, path + 'upload-url', 200, payload,
                                  {'Idempotency-Key': str(uuid.uuid4())})
        with urllib.request.urlopen(urllib.request.Request(administrative['uploadUrl'], data=b'abcdefghij',
                                    method='PUT', headers={'Content-Type': 'text/plain'}), timeout=20) as response:
            e.require(response.status // 100 == 2, 'Administrative signed PUT failed')
        positive(owner, path + 'confirm', 201, dict(confirm, objectKey=administrative['objectKey']))
        e.require(self.sql('tenant_db', "SELECT count(*) FROM taska.quota_reservations WHERE tenant_id='%s' AND resource='STORAGE_BYTES' AND storage_bypassed_limit AND state='COMMITTED' AND storage_actual_bytes=10;" % tenant).strip() == '1', 'Administrative usage was not recorded')
        self.sql('auth_db', "UPDATE taska.users SET global_role='USER' WHERE id='%s';UPDATE taska.web_sessions SET assurance='PASSWORD' WHERE user_id='%s';" % (actor, actor))
        # Отзыв реального membership закрывает даже тёплый confirm без нового расхода.
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, actor))
        response = owner.request('POST', path + 'confirm', body=dict(confirm, objectKey=late['objectKey']))
        e.require(response.status in (401, 403), 'Revoked membership retained confirm access')
        return {'publicRestSignedPutConfirm': 'PASS', 'tenantAndProjectIsolation': 'PASS',
                'retryAndAccountingOnce': 'PASS', 'payloadConflict': 'PASS', 'membershipRevocation': 'PASS', 'primaryAdminBypassAccounting': 'PASS', 'requestIdFallback': 'PASS', 'actorOwnership': 'PASS', 'mimeAdmission': 'PASS', 'quotaRefusalAccountingUnchanged': 'PASS', 'transient503Retries': transients, 'readinessTransients': readiness_transients}


def main():
    t.TrustedLocal = AttachmentUploadLocal
    t.main()


if __name__ == '__main__':
    main()
