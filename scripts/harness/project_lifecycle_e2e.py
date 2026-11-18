"""TAS-341: owned native archive/restore, current authority, CAS/replay and quota preservation."""
import base64
import concurrent.futures
import json
import uuid

import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal


class ProjectLifecycleLocal(IssueAclLocal):
    def scenarios(self):
        actor, clients = self.seed()
        projects = []
        for tenant, client in clients:
            client.timeout = 60
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            projects.append(client.expect('POST', '/api/v1/projects', 201,
                {'projectKey': 'ARC', 'name': 'Owned lifecycle'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id'])
        tenant, owner = clients[0]
        _, other = clients[1]
        project, foreign = projects
        path = '/api/v1/projects/' + project
        self.ready_workflow(owner, project)
        issue = owner.expect('POST', path + '/issues', 201,
            {'issueType': 'TASK', 'summary': 'Retained lifecycle history', 'description': 'Owned lifecycle fixture', 'priority': 'MEDIUM'},
            {'Idempotency-Key': str(uuid.uuid4())}).json()['id']
        members = []
        for index, role in enumerate(('VIEWER', 'ADMIN')):
            member = str(uuid.uuid4())
            self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','lifecycle-%s@harness.invalid','Owned lifecycle member','ACTIVE','USER');" % (member, index))
            self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, member))
            owner.expect('POST', path + '/members', 201, {'userId': member, 'role': role})
            client, _ = self.member_client(tenant, member, 1)
            members.append((member, client))
        viewer_id, viewer = members[0]
        admin_id, project_admin = members[1]
        self.sql('project_db', "DELETE FROM taska.project_members WHERE tenant_id='%s' AND project_id='%s' AND user_id='%s';" % (tenant, project, actor))
        initial = owner.expect('GET', path, 200).json()
        def headers(version, key=None):
            return {'If-Match': '"' + str(version) + '"', 'Idempotency-Key': key or str(uuid.uuid4())}
        def retained():
            return (self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip(),
                self.sql('issue_db', "SELECT jsonb_build_array((SELECT to_jsonb(i) FROM taska.issues i WHERE id='%s'),(SELECT jsonb_agg(to_jsonb(h) ORDER BY id) FROM taska.issue_history h WHERE issue_id='%s'));" % (issue, issue)).strip())
        baseline = retained()
        key = str(uuid.uuid4())
        original = headers(initial['version'], key)
        archived = owner.expect('DELETE', path, 200, headers=original).json()
        e.require(archived.get('archivedAt') and int(archived['version']) == int(initial['version']) + 1,
                  'Archive did not advance its version/state')
        e.require(owner.expect('DELETE', path, 200, headers=original).json() == archived, 'Archive replay changed response')
        e.require(owner.expect('DELETE', path, 200, headers=headers(archived['version'])).json() == archived, 'No-op archive changed response')
        owner.expect('GET', path + '/context', 200)
        restored = owner.expect('POST', path + '/restore', 200, headers=headers(archived['version'])).json()
        e.require(not restored.get('archivedAt'), 'Restore retained archive state')
        e.require(owner.expect('DELETE', path, 200, headers=original).json() == archived, 'Old receipt was replaced after restore')
        e.require(not owner.expect('GET', path, 200).json().get('archivedAt'), 'Replay repeated archive effect')
        owner.expect('POST', path + '/restore', 409, headers=original)
        owner.expect('DELETE', path, 412, headers=headers(initial['version']))
        owner.expect('POST', path + '/restore', 428, headers={'Idempotency-Key': str(uuid.uuid4())})
        owner.expect('DELETE', '/api/v1/projects/' + foreign, 404, headers=headers(restored['version']))
        viewer.expect('DELETE', path, 403, headers=headers(restored['version']))
        # Two independent commands with the same version cannot both mutate the project.
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(owner.request, 'DELETE', path, None, headers(restored['version'])) for _ in range(2)]
            responses = [f.result() for f in futures]
        e.require(sorted(r.status for r in responses) == [200, 412], 'Concurrent stale archive overwrote another command')
        current = owner.expect('GET', path, 200).json()
        current = owner.expect('POST', path + '/restore', 200, headers=headers(current['version'])).json()
        # Existing last-project-ADMIN protection remains active even for an implicit company admin.
        denied = owner.request('PATCH', path + '/members/' + admin_id, {'role': 'MEMBER'})
        e.require(denied.status in (400, 409), 'Last project ADMIN guard was lost')
        project_admin.expect('DELETE', path, 200, headers=headers(current['version']))
        current = owner.expect('GET', path, 200).json()
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, admin_id))
        e.require(project_admin.request('POST', path + '/restore', headers=headers(current['version'])).status in (401, 403),
                  'Revoked membership restored project')
        # Account role alone is insufficient; explicit web administrative MFA activates the audited path.
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';" % viewer_id)
        viewer.expect('POST', path + '/restore', 403, headers=headers(current['version']))
        claims = json.loads(base64.urlsafe_b64decode(viewer.token.split('.')[1] + '=='))
        self.sql('auth_db', "UPDATE taska.web_sessions SET assurance='ADMIN_EMAIL_MFA' WHERE id=(SELECT web_session_id FROM taska.tenant_sessions WHERE id='%s');" % claims['sid'])
        viewer.expect('POST', path + '/restore', 200, headers=headers(current['version']))
        e.require(int(self.sql('project_db', "SELECT count(*) FROM taska.project_authorization_audit WHERE tenant_id='%s' AND actor_id='%s' AND action='project.restore';" % (tenant, viewer_id)).strip()) > 0,
                  'Administrative restore lacks audit')
        e.require(retained() == baseline, 'Archive/restore changed quota or issue/history')
        e.require(not other.expect('GET', '/api/v1/projects/' + foreign, 200).json().get('archivedAt'), 'Foreign project changed')
        return {'scenario': 'project-lifecycle', 'archiveRestore': 'PASS', 'implicitTenantAdmin': 'PASS',
                'replayConflictAndConcurrency': 'PASS', 'currentMembershipAndLastAdmin': 'PASS',
                'administrativeMfaAndAudit': 'PASS', 'quotaAndHistoryPreserved': 'PASS'}


if __name__ == '__main__':
    t.TrustedLocal = ProjectLifecycleLocal
    t.main()
