"""TAS-342: owned native workflow REST/direct-RPC acceptance with real primaries and RLS.

Uses the signed local harness lifecycle; never connects to a shared environment.
Issue status migration is not claimed: the native issue owner is a separate activation boundary.
"""
import base64
import json
import uuid
import zipfile

import e2e as e
import trusted_rpc_e2e as t
from project_acl_e2e import ProjectAclLocal


class WorkflowAclLocal(ProjectAclLocal):
    def configure_container(self, name, service):
        super().configure_container(name, service)
        if name == 'workflow-service':
            service.setdefault('ports', []).append('127.0.0.1::9090')

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        for rpc, action, audience, purpose, mode, scopes in (
            ('ru.taska.b2b.v1.PlatformService/GetPlatformConfigurationSnapshot', 'getPlatformConfigurationSnapshot', 'tenant-service', 'admin', 'NONE', ['read']),
            ('ru.taska.b2b.v1.EntitlementService/GetEntitlementSnapshot', 'getEntitlementSnapshot', 'billing-service', 'billing', 'BOUND', ['read']),
            ('ru.taska.b2b.v1.BillingService/ReadReferencedPlanVersion', 'readReferencedPlanVersion', 'billing-service', 'billing', 'NONE', ['read', 'catalog:read'])):
            self.edge('workflow-service', audience, rpc, action, ['SERVICE'], scopes, purpose, mode)
        self.refresh()

    def workflows(self, client, project):
        return client.expect('GET', '/api/v1/projects/' + project + '/workflows', 200).json()

    def scenarios(self):
        actor, clients = self.seed()
        projects = []
        for tenant, client in clients:
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            projects.append(client.expect('POST', '/api/v1/projects', 201,
                {'projectKey': 'WF', 'name': 'Owned workflow fixture'}, {'Idempotency-Key': str(uuid.uuid4())}).json()['id'])
        tenant, owner = clients[0]
        foreign_tenant, other = clients[1]
        project, foreign_project = projects
        initial = self.workflows(owner, project)
        foreign = self.workflows(other, foreign_project)['items'][0]['workflow']
        workflow = initial['items'][0]['workflow']
        keys = {node['id']: node['statusKey'] for node in workflow['statuses']}
        payload = {'name': 'Owned custom workflow', 'issueTypes': ['TASK', 'BUG', 'STORY'],
            'statuses': [{k: node[k] for k in ('statusKey', 'name', 'category', 'sortOrder')}
                         for node in workflow['statuses']],
            'transitions': [{'fromStatusKey': keys[edge['fromStatusId']],
                'toStatusKey': keys[edge['toStatusId']], 'name': edge['name'],
                'sortOrder': edge['sortOrder'], 'hidden': edge.get('hidden', False)}
                for edge in workflow['transitions']]}
        headers = {'X-Request-Id': str(uuid.uuid4())}
        created = owner.expect('POST', '/api/v1/projects/' + project + '/workflows', 201, payload, headers).json()
        e.require(owner.expect('POST', '/api/v1/projects/' + project + '/workflows', 201, payload, headers).json() == created,
                  'Create replay changed its frozen graph/response')
        owner.expect('POST', '/api/v1/projects/' + project + '/workflows', 409,
            dict(payload, name='Conflicting create'), headers)
        restored = owner.expect('DELETE', '/api/v1/projects/' + project + '/workflows/' + created['workflow']['id'],
            200, {}).json()['workflow']
        e.require(restored['id'] not in (workflow['id'], created['workflow']['id']),
                  'Delete reused a mutable/shared fallback graph')
        initial = self.workflows(owner, project)
        workflow = initial['items'][0]['workflow']
        path = '/api/v1/projects/' + project + '/workflows/' + workflow['id']
        e.require(len(initial['items']) == 1 and len(workflow['statuses']) == 3
                  and len(workflow['transitions']) == 6, 'Native grouping lost the initialized graph')
        owner.expect('PATCH', '/api/v1/projects/' + project + '/workflows/' + foreign['id'], 404, {'name': 'Denied'})
        owner.expect('PATCH', path + '/statuses/' + foreign['statuses'][0]['id'], 404, {'name': 'Denied'})
        owner.expect('DELETE', path + '/transitions/' + foreign['transitions'][0]['id'], 404)
        owner.expect('GET', '/api/v1/projects/' + foreign_project + '/workflows', 404)
        e.require(self.workflows(owner, project) == initial, 'Foreign IDs changed a workflow')
        e.require(self.workflows(other, foreign_project)['items'][0]['workflow'] == foreign,
                  'Foreign graph changed after denied mutations')
        changed = owner.expect('PATCH', path, 200, {'name': 'Renamed workflow', 'version': workflow['version']}).json()['workflow']
        owner.expect('PATCH', path, 409, {'name': 'Stale', 'version': workflow['version']})
        e.require(changed['version'] == workflow['version'] + 1, 'Metadata version was not increased exactly once')
        status = owner.expect('POST', path + '/statuses', 201,
            {'statusKey': 'QA', 'name': 'Quality', 'category': 'DONE', 'sortOrder': 90}).json()['workflow']
        e.require(next(n for n in status['statuses'] if n['statusKey'] == 'QA')['category'] == 'DONE',
                  'DONE semantics were inferred from the status key')
        edge = owner.expect('POST', path + '/transitions', 201,
            {'fromStatusKey': 'TODO', 'toStatusKey': 'QA', 'name': 'Review', 'sortOrder': 90, 'hidden': True}).json()['workflow']
        e.require(len(edge['transitions']) == 7, 'Hidden transition was lost from editor response')
        runtime = owner.expect('GET', '/api/v1/projects/' + project + '/workflow?issueType=TASK', 200).json()
        e.require(len(runtime['transitions']) == 6, 'Hidden transition leaked into the runtime listing')
        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','workflow-reader@harness.invalid','Workflow reader','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, member))
        viewer, credential = self.member_client(tenant, member, 1)
        viewer.expect('GET', '/api/v1/projects/' + project + '/workflows', 404)
        owner.expect('POST', '/api/v1/projects/' + project + '/members', 201, {'userId': member, 'role': 'VIEWER'})
        self.workflows(viewer, project)
        viewer.expect('PATCH', path, 403, {'name': 'Denied'})
        owner.expect('PATCH', '/api/v1/projects/' + project + '/members/' + member, 200, {'role': 'ADMIN'})
        viewer.expect('PATCH', path, 200, {'name': 'Project admin edit'})
        owner.expect('PATCH', '/api/v1/projects/' + project + '/members/' + member, 200, {'role': 'VIEWER'})
        viewer.expect('PATCH', path, 403, {'name': 'Stale admin denied'})
        before_flags = self.workflows(owner, project)
        self.sql('tenant_db', "INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,2,value_type,'false'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='WORKFLOW_CONFIGURATION';" % actor)
        owner.expect('PATCH', path, 403, {'name': 'Flag denied'})
        owner.expect('POST', path + '/statuses', 403,
            {'statusKey': 'DENIED', 'name': 'Denied', 'category': 'TODO', 'sortOrder': 100})
        e.require(owner.expect('POST', '/api/v1/projects/' + project + '/workflows', 201, payload, headers).json() == created,
                  'Configuration flag-off broke an exact completed create receipt')
        owner.expect('POST', '/api/v1/projects/' + project + '/workflows', 403, payload,
                     {'X-Request-Id': str(uuid.uuid4())})
        e.require(self.workflows(owner, project) == before_flags, 'Flag-off mutation changed the graph')
        probe = self.direct_workflow_probe(owner, actor, tenant, project, foreign_project, workflow['id'])
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, member))
        e.require(viewer.request('GET', '/api/v1/projects/' + project + '/workflows').status in (401, 403),
                  'Suspended member retained workflow access')
        self.compose('stop', 'project-service')
        owner.expect('GET', '/api/v1/projects/' + project + '/workflows', 503)
        self.compose('start', 'project-service')
        self.wait_ready('http://' + self.compose('port', 'project-service', '8080').strip())
        t.wait_protected_read(owner, '/api/v1/projects/' + project + '/workflows', 200)
        e.require(self.workflows(owner, project) == before_flags, 'Primary outage changed the graph')
        return {'nativeWorkflowRest': 'PASS', 'createReplayAndPrivateFallback': 'PASS', 'foreignNestedIds': 'PASS', 'currentProjectRoles': 'PASS',
                'flagOffRuntimePreserved': 'PASS', 'doneCategory': 'PASS', 'primaryOutage': 'PASS', **probe}

    def direct_workflow_probe(self, client, actor, tenant, project, foreign, workflow):
        claims = json.loads(base64.urlsafe_b64decode(client.token.split('.')[1] + '=='))
        libraries = self.material / 'workflow-probe-libs'
        libraries.mkdir(mode=0o700)
        with zipfile.ZipFile(self.dir / 'jars/workflow-service.jar') as archive:
            for item in archive.infolist():
                if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                    (libraries / item.filename.rsplit('/', 1)[1]).write_bytes(archive.read(item))
        parameters = self.material / 'workflow-probe.json'
        e.private_json(parameters, {'material': str(self.material), 'actor': actor, 'tenant': tenant,
            'credential': claims['sid'], 'project': project, 'foreignProject': foreign, 'workflow': workflow,
            'authPort': int(self.compose('port', 'auth-service', '9090').strip().rsplit(':', 1)[1]),
            'workflowPort': int(self.compose('port', 'workflow-service', '9090').strip().rsplit(':', 1)[1])})
        output = self.command('java', '--class-path', str(libraries / '*'),
            str(self.root / 'scripts/harness/WorkflowAclProbe.java'), str(parameters))
        return json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{')))


def main():
    t.TrustedLocal = WorkflowAclLocal
    t.main()


if __name__ == '__main__':
    main()
