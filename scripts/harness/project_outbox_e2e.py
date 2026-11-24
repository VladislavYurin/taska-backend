"""TAS-545: изолированная native проверка создания и outbox проекта."""
import json
import time
import uuid
import e2e as e
import trusted_rpc_e2e as t
from issue_acl_e2e import IssueAclLocal

class ProjectOutboxLocal(IssueAclLocal):
    def configure_service(self, name, env, props):
        super().configure_service(name, env, props)
        if name == 'project-service':
            # This scenario checks synchronous compensation. Recovery concurrency
            # is covered separately by the broad project ACL runner.
            props['taska.projects.recovery-tenants'] = ''

    def domain_state(self, tenant):
        return (
            json.loads(self.sql('project_db', "SELECT jsonb_build_array((SELECT count(*) FROM taska.projects WHERE tenant_id='%s'),(SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s'),(SELECT count(*) FROM taska.project_members WHERE tenant_id='%s'))::text;" % (tenant, tenant, tenant)).strip()),
            self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip())

    def scenarios(self):
        initial = e.ASSERTIONS
        actor, clients = self.seed()
        projects = []
        for tenant, client in clients:
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
            key = str(uuid.uuid4())
            body = {'projectKey': 'OUT', 'name': 'Owned outbox fixture'}
            project = client.expect('POST', '/api/v1/projects', 201, body, {'Idempotency-Key': key}).json()['id']
            e.require(self.sql('project_db', "SELECT tenant_id FROM taska.projects WHERE id='%s';" % project).strip() == tenant, 'Project tenant provenance differs')
            e.require(self.sql('project_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND aggregate_id='%s' AND event_type='ProjectCreated';" % (tenant, project)).strip() == '1', 'Creation event missing or duplicated')
            before = self.domain_state(tenant)
            e.require(before[1] == '1/0', 'Creation quota differs')
            replay = client.expect('POST', '/api/v1/projects', 201, body, {'Idempotency-Key': key}).json()
            e.require(replay['id'] == project, 'Replay changed project identity')
            client.expect('POST', '/api/v1/projects', 409, dict(body, name='Conflict'), {'Idempotency-Key': key})
            e.require(self.domain_state(tenant) == before, 'Replay or conflict changed projects/members/outbox/quota')
            projects.append(project)
        tenant, owner = clients[0]
        owner.expect('GET', '/api/v1/projects/' + projects[1], 404)
        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','owned-outbox@harness.invalid','Owned member','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (tenant, member))
        member_client, _ = self.member_client(tenant, member, 1)
        before = self.domain_state(tenant)
        member_client.expect('POST', '/api/v1/projects', 403, {'projectKey': 'DENIED', 'name': 'Denied'},
                             {'Idempotency-Key': str(uuid.uuid4())})
        e.require(self.domain_state(tenant) == before, 'Denied creation changed projects/members/outbox/quota')

        failed = self.recovery_tenant
        self.sql('tenant_db', "BEGIN;INSERT INTO taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id) VALUES('%s','Owned rollback','%s','ACTIVE','FREE','%s','%s',decode('00','hex'),'%s');INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');COMMIT;" % (failed, actor, str(uuid.uuid4()), actor, str(uuid.uuid4()), failed, actor))
        self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % failed)
        rejected, _ = self.member_client(failed, actor, 1)
        t.wait_protected_read(rejected, '/api/v1/projects/' + str(uuid.uuid4()), 404)
        # Missing workflow copy is FAILED_PRECONDITION in the legacy JSON
        # representation. The SQL checks establish actual compensation.
        rejected.expect('POST', '/api/v1/projects', 400, {'projectKey': 'FAIL', 'name': 'No workflow template'}, {'Idempotency-Key': str(uuid.uuid4())})
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and self.domain_state(failed)[1] != '0/0':
            time.sleep(.5)
        failed_state = self.domain_state(failed)
        e.require(failed_state[0][0] == 0 and failed_state[0][2] == 0 and failed_state[1] == '0/0',
                  'Confirmed rejection retained project/member/quota: ' + repr(failed_state))
        # Provisioning is a committed phase; its historical event is retained.
        # A failed bootstrap must never publish the successful opening/creation.
        e.require(self.sql('project_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND event_type IN ('ProjectCreated','ProjectAdmissionOpened');" % failed).strip() == '0',
                  'Rejected bootstrap emitted successful creation/opening')
        e.require(self.sql('project_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND event_type='ProjectProvisioned' AND payload->>'projectId'=aggregate_id::text;" % failed).strip() == '1',
                  'Compensation lost or duplicated the committed provisioning history')
        e.require(self.sql('workflow_db', "SELECT count(*) FROM taska.project_initializations WHERE tenant_id='%s' AND state='CANCELLED';" % failed).strip() == '1', 'Workflow cancellation tombstone missing')
        return {'scenario': 'native-project-outbox', 'nativeCreateTenantProvenance': 'PASS', 'singleOutboxEvent': 'PASS',
                'replayAndConflictState': 'PASS', 'crossTenantDenied': 'PASS',
                'memberDeniedState': 'PASS', 'bootstrapCompensation': 'PASS',
                'testCount': e.ASSERTIONS - initial,
                'scope': 'Собственная REST/mTLS/JWS/RLS fixture; project/outbox rows и квота. Агрегация context, доставка Kafka и stage не проверяются.'}

def main():
    t.main(local_cls=ProjectOutboxLocal, description=__doc__, scenario='native-project-outbox')


if __name__ == '__main__':
    main()
