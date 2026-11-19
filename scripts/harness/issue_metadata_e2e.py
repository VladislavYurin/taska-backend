"""TAS-363: собственные REST/mTLS/RLS fixtures меток, подписок и current recipients."""
import json
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor

import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal


class IssueMetadataLocal(IssueAclLocal):
    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        self.edge('issue-service', 'project-service', 'ru.taska.b2b.v1.ProjectRecipientService/Check',
                  'checkProjectRecipients', ['SERVICE'], ['memberships:read', 'profiles:read'], 'membership')
        for purpose in ('NOTIFICATION_DELIVERY', 'outbox'):
            # Второй read-only grant нужен только для отрицательной проверки application purpose.
            self.edge('notification-service', 'issue-service', 'ru.taska.b2b.v1.IssueRecipientService/Resolve',
                      'resolveIssueRecipients', ['SERVICE'], ['read', 'notifications:recipients'], purpose)
        self.refresh()

    def ready(self, client, path):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            response = client.request('GET', path)
            if response.status == 200:
                return response.json()
            e.require(response.status == 503, 'Metadata read readiness returned %s' % response.status)
            time.sleep(1)
        raise e.Blocked('Metadata protected read readiness deadline exceeded')

    def recipients_probe(self, tenant, foreign_tenant, issue, expected):
        libraries = self.material / 'metadata-probe-libs'
        if not libraries.exists():
            libraries.mkdir(mode=0o700)
            with zipfile.ZipFile(self.dir / 'jars/issue-service.jar') as archive:
                for item in archive.infolist():
                    if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                        (libraries / item.filename.rsplit('/', 1)[1]).write_bytes(archive.read(item))
        parameters = self.material / 'metadata-recipients.json'
        e.private_json(parameters, {'material': str(self.material), 'tenant': tenant, 'foreignTenant': foreign_tenant,
                                  'issue': issue, 'expected': expected,
                                  'authPort': int(self.compose('port', 'auth-service', '9090').strip().rsplit(':', 1)[1]),
                                  'issuePort': int(self.compose('port', 'issue-service', '9090').strip().rsplit(':', 1)[1])})
        result = e.run_command(['java', '--class-path', str(libraries / '*'),
                                str(self.root / 'scripts/harness/IssueRecipientsProbe.java'), str(parameters)],
                               self.root, 120, diagnostic_path=self.dir / 'metadata-probe-diagnostic.json')
        return json.loads(next(line for line in reversed(result.splitlines()) if line.startswith('{')))

    def scenarios(self):
        actor, clients = self.seed()
        fixtures = []
        for tenant, client in clients:
            project, issue, warm = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('project_db', "INSERT INTO taska.projects(tenant_id,id,project_key,name,created_by,admission_state) VALUES('%s','%s','META','Owned metadata','%s','OPEN');INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','ADMIN','%s',1);" % (tenant, project, actor, tenant, project, actor, actor))
            self.sql('issue_db', "INSERT INTO taska.project_counters(tenant_id,project_id,next_issue_number,lifetime_created) VALUES('%s','%s',3,2);" % (tenant, project))
            for number, item in ((1, issue), (2, warm)):
                self.sql('issue_db', "INSERT INTO taska.issues(tenant_id,id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id) VALUES('%s','%s','%s',%s,'META-%s','TASK','Owned metadata fixture','TODO','MEDIUM','%s');" % (tenant, item, project, number, number, actor))
            self.sql('issue_db', "INSERT INTO taska.issue_watchers(tenant_id,project_id,issue_id,user_id,created_by) VALUES('%s','%s','%s','%s','%s');" % (tenant, project, warm, actor, actor))
            self.ready(client, '/api/v1/projects/' + project)
            # Отдельная read-only fixture прогревает новый primary путь до первой измеряемой записи.
            self.ready(client, '/api/v1/projects/' + project + '/issues/' + warm + '/watchers')
            fixtures.append((tenant, client, project, issue))
        tenant, owner, project, issue = fixtures[0]
        foreign_tenant, foreign, foreign_project, foreign_issue = fixtures[1]
        path = '/api/v1/projects/' + project
        issue_path = path + '/issues/' + issue
        labels_path = issue_path + '/labels'
        watchers_path = issue_path + '/watchers'

        members = {}
        for role in ('MEMBER', 'VIEWER', 'OUTSIDE'):
            user = str(uuid.uuid4())
            self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','%s@harness.invalid','Owned metadata participant','ACTIVE','USER');" % (user, user))
            self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, user))
            if role != 'OUTSIDE':
                self.sql('project_db', "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version) VALUES('%s','%s','%s','%s','%s',1);" % (tenant, project, user, role, actor))
            client, _ = self.member_client(tenant, user, 1)
            self.ready(client, '/api/v1/tenants/' + tenant + '/capabilities')
            members[role] = (user, client)
        member_id, member = members['MEMBER']
        viewer_id, viewer = members['VIEWER']
        outside_id, outside = members['OUTSIDE']
        self.ready(member, '/api/v1/issues/' + issue)
        self.ready(viewer, '/api/v1/issues/' + issue)

        label = owner.expect('POST', path + '/labels', 201, {'name': 'same-name', 'color': '#112233'}).json()['id']
        foreign_label = foreign.expect('POST', '/api/v1/projects/' + foreign_project + '/labels', 201,
                                       {'name': 'same-name', 'color': '#112233'}).json()['id']
        member.expect('POST', path + '/labels', 403, {'name': 'denied', 'color': '#112233'})
        viewer.expect('POST', labels_path, 403, {'labelId': label})
        member.expect('POST', labels_path, 404, {'labelId': foreign_label})
        foreign.expect('GET', labels_path, 404)
        owner.expect('GET', '/api/v1/projects/' + foreign_project + '/issues/' + issue + '/labels', 404)
        owner.expect('PATCH', path + '/labels/' + label, 200, {'name': 'renamed', 'color': '#445566'})
        # Нереализованные search/list не читают legacy данные и сохраняют закрытую границу.
        member.expect('GET', '/api/v1/issues/search?query=Owned&projectId=' + project, 503)
        member.expect('GET', path + '/issues', 503)
        with ThreadPoolExecutor(max_workers=3) as workers:
            results = list(workers.map(lambda _: member.expect('POST', labels_path, 201, {'labelId': label}).json(), range(3)))
        e.require(len({row['createdAt'] for row in results}) == 1, 'Concurrent attach changed its receipt')
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.issue_labels WHERE tenant_id='%s' AND issue_id='%s';" % (tenant, issue)).strip() == '1', 'Label link duplicated')
        panel = self.ready(member, '/api/v1/issues/' + issue)['issue']
        e.require([row['id'] for row in panel['labels']] == [label], 'Panel lost scoped labels')

        viewer.expect('PUT', watchers_path + '/me', 403)
        member.expect('POST', watchers_path, 403, {'userId': viewer_id})
        owner.expect('POST', watchers_path, 400, {'userId': outside_id})
        with ThreadPoolExecutor(max_workers=3) as workers:
            results = list(workers.map(lambda _: member.expect('PUT', watchers_path + '/me', 200).json(), range(3)))
        e.require(len({row['watcher']['id'] for row in results}) == 1, 'Concurrent watch duplicated identity')
        owner.expect('POST', watchers_path, 200, {'userId': actor})
        owner.expect('POST', watchers_path, 200, {'userId': viewer_id})
        e.require(self.sql('issue_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND aggregate_id='%s' AND event_type='WATCHED';" % (tenant, issue)).strip() == '3', 'Concurrent watch duplicated event')
        e.require(self.ready(member, '/api/v1/issues/' + issue)['issue']['isWatching'], 'Panel lost watch state')
        owner.expect('GET', '/api/v1/projects/' + foreign_project + '/issues/' + issue + '/watchers', 404)
        outside.expect('GET', watchers_path, 404)

        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member_id))
        owner.expect('POST', watchers_path, 400, {'userId': member_id})
        e.require(member.request('PUT', watchers_path + '/me').status in (401, 403), 'Old membership grant remained usable')
        current = owner.expect('GET', watchers_path, 200).json()
        e.require(current['totalCount'] == 2 and {row['userId'] for row in current['watchers']} == {actor, viewer_id}, 'Removed target leaked into watchers count/list')
        # Старый project grant не оживает после восстановления membership с новой generation.
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='ACTIVE',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member_id))
        owner.expect('POST', watchers_path, 400, {'userId': member_id})
        current = owner.expect('GET', watchers_path, 200).json()
        e.require(current['totalCount'] == 2, 'Old project grant revived after membership restoration')
        self.sql('auth_db', "UPDATE taska.users SET status='BLOCKED' WHERE id='%s';" % viewer_id)
        owner.expect('POST', watchers_path, 400, {'userId': viewer_id})
        current = owner.expect('GET', watchers_path, 200).json()
        e.require(current['totalCount'] == 1 and current['watchers'][0]['userId'] == actor, 'Blocked account retained recipient access')
        probe = self.recipients_probe(tenant, foreign_tenant, issue, [actor])

        owner.expect('DELETE', watchers_path + '/me', 200)
        owner.expect('DELETE', watchers_path + '/me', 200)
        owner.expect('DELETE', labels_path + '/' + label, 204)
        owner.expect('DELETE', labels_path + '/' + label, 204)
        owner.expect('DELETE', path + '/labels/' + label, 204)
        owner.expect('DELETE', path + '/labels/' + label, 204)
        empty_labels = owner.expect('GET', labels_path, 200).json()
        e.require(empty_labels['items'] == [] and empty_labels['totalCount'] == 0, 'Deleted label remained visible')
        payload = self.sql('issue_db', "SELECT coalesce(string_agg(payload::text,' '),'') FROM taska.outbox_events WHERE tenant_id='%s' AND aggregate_id='%s';" % (tenant, issue))
        e.require('Owned metadata' not in payload and 'same-name' not in payload and 'displayName' not in payload and 'email' not in payload,
                  'Outbox disclosed content or profile')
        self.sql('project_db', "UPDATE taska.projects SET archived_at=now() WHERE tenant_id='%s' AND id='%s';" % (tenant, project))
        owner.expect('POST', path + '/labels', 403, {'name': 'archived-denied', 'color': '#112233'})
        self.sql('project_db', "UPDATE taska.projects SET archived_at=NULL WHERE tenant_id='%s' AND id='%s';" % (tenant, project))
        pause = str(uuid.uuid4())
        self.sql('tenant_db', "INSERT INTO taska.tenant_restrictions(tenant_id,kind,source_id,active,version,reason_code,created_by,created_at) VALUES('%s','OWNER_PAUSE','%s',true,1,'OWNER_PAUSE','%s',now());UPDATE taska.tenants SET restriction_version=restriction_version+1,version=version+1 WHERE id='%s';" % (tenant, pause, actor, tenant))
        owner.expect('POST', path + '/labels', 403, {'name': 'pause-denied', 'color': '#112233'})
        owner.expect('PUT', watchers_path + '/me', 403)
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';UPDATE taska.web_sessions SET assurance='ADMIN_EMAIL_MFA' WHERE user_id='%s';" % (actor, actor))
        owner.expect('POST', path + '/labels', 201, {'name': 'audited-admin-bypass', 'color': '#112233'})
        e.require(int(self.sql('issue_db', "SELECT count(*) FROM taska.issue_authorization_audit WHERE tenant_id='%s' AND actor_id='%s' AND action='createProjectLabel';" % (tenant, actor)).strip()) > 0, 'Administrative metadata bypass was not audited')
        self.sql('auth_db', "UPDATE taska.users SET global_role='USER' WHERE id='%s';UPDATE taska.web_sessions SET assurance='PASSWORD' WHERE user_id='%s';" % (actor, actor))
        return {**probe, 'publicLabelsAndWatchers': 'PASS', 'tenantProjectIsolation': 'PASS',
                'nativeRolePolicy': 'PASS', 'retryAndConcurrentOnce': 'PASS', 'currentTargetAndAccount': 'PASS',
                'scopedPanelAndPrivacy': 'PASS', 'searchListFailClosed': 'PASS', 'archivedWriteDenial': 'PASS', 'readOnlyAndAuditedBypass': 'PASS'}


def main():
    t.TrustedLocal = IssueMetadataLocal
    t.main()


if __name__ == '__main__':
    main()
