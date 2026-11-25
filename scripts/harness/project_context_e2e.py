"""TAS-533: owned REST/mTLS context read, workflow failure and recovery."""
import json
import base64
import uuid
import zipfile

import e2e as e
import trusted_rpc_e2e as t


class ProjectContextLocal(t.TrustedLocal):
    def configure_container(self, name, service):
        if name in ('auth-service', 'project-service'):
            service.setdefault('ports', []).append('127.0.0.1::9090')

    def context(self, client, actor, tenant, project):
        libraries = self.material / 'context-probe-libs'
        if not libraries.exists():
            libraries.mkdir(mode=0o700)
            with zipfile.ZipFile(self.dir / 'jars/project-service.jar') as archive:
                for item in archive.infolist():
                    if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                        (libraries / item.filename.rsplit('/', 1)[1]).write_bytes(archive.read(item))
        claims = json.loads(base64.urlsafe_b64decode(client.token.split('.')[1] + '=='))
        parameters = self.material / 'context-probe.json'
        e.private_json(parameters, {'material': str(self.material), 'actor': actor, 'tenant': tenant,
            'credential': claims['sid'], 'project': project,
            'authPort': int(self.compose('port', 'auth-service', '9090').strip().rsplit(':', 1)[1]),
            'projectPort': int(self.compose('port', 'project-service', '9090').strip().rsplit(':', 1)[1])})
        result = e.run_command(['java', '--class-path', str(libraries / '*'),
            str(self.root / 'scripts/harness/ProjectContextProbe.java'), str(parameters)], self.root, 90,
            diagnostic_path=self.dir / 'context-probe-diagnostic.json')
        return json.loads(next(line for line in reversed(result.splitlines()) if line.startswith('{')))

    def prepare_extra_databases(self):
        routes = json.loads((self.root / 'grpc-common-lib/src/main/resources/security/rpc-route-policies.json').read_text())['routes']
        for route in routes:
            if route['audience'] == 'auth-service' and route['rpc'].endswith('/GetUserDetailsByIds'):
                self.edge('project-service', route['audience'], route['rpc'], route['action'],
                          ['WEB_TENANT'], ['read', 'write'])
        self.refresh()

    def scenarios(self):
        actor, clients = self.seed()
        tenant, client = clients[0]
        project = str(uuid.uuid4())
        # Owned fixture only; the public create saga has a separate acceptance scope.
        self.sql('project_db', """
            INSERT INTO taska.projects(id,tenant_id,project_key,name,description,created_by,color)
            VALUES('%s','%s','CTX%s','Owned context','Owned context','%s','#0052CC');
            INSERT INTO taska.project_members(project_id,tenant_id,user_id,role,added_by)
            VALUES('%s','%s','%s','ADMIN','%s');
            INSERT INTO taska.project_issue_types(project_id,tenant_id,code,name,color,sort_order)
            VALUES('%s','%s','TASK','Task','blue',0);
            """ % (project, tenant, self.state['runId'][:12].upper(), actor,
                   project, tenant, actor, actor, project, tenant))
        path = '/api/v1/projects/' + project + '/context'
        t.wait_protected_read(client, '/api/v1/projects/' + project, 200)
        baseline = self.context(client, actor, tenant, project)
        ids = {name: self.compose('ps', '-q', name).strip() for name in self.modules}

        def snapshot():
            return self.sql('project_db',
                "SELECT jsonb_build_object('project',(SELECT to_jsonb(p) FROM taska.projects p"
                " WHERE id='%s'),'members',(SELECT jsonb_agg(to_jsonb(m) ORDER BY user_id)"
                " FROM taska.project_members m WHERE project_id='%s'),"
                "'outboxCount',(SELECT count(*) FROM taska.outbox_events));" % (project, project)).strip()

        before = snapshot()
        self.compose('stop', 'workflow-service', timeout=60)
        response = client.request('GET', path)
        e.require(response.status in (503, 504), 'Unavailable workflow was hidden by a successful context')
        e.require(bool(response.json().get('requestId')), 'Workflow failure lost its request ID')
        e.require(snapshot() == before, 'Failed context read changed project/membership/outbox state')
        self.compose('up', '-d', '--no-deps', 'workflow-service', timeout=120)
        self.wait_ready('http://' + self.compose('port', 'workflow-service', '8080').strip())
        recovered = self.context(client, actor, tenant, project)
        e.require(recovered == baseline, 'Workflow recovery changed the project context')
        e.require(snapshot() == before, 'Recovered context read changed persisted state')
        e.require({name: self.compose('ps', '-q', name).strip() for name in self.modules} == ids,
                  'Service containers changed during the probe')
        return {'scenario': 'project-context-workflow-errors', 'normalRpcContext': 'PASS',
                'unavailableWorkflow': 'PASS', 'requestId': 'PASS', 'statePreservation': 'PASS',
                'recovery': 'PASS', 'scope': 'Legacy ProjectServiceImpl context RPC; REST failure mapping. Native task-quota enrichment is a separate profile.'}


if __name__ == '__main__':
    t.TrustedLocal = ProjectContextLocal
    t.main()
