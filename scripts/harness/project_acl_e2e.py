"""TAS-340: owned seven-application native project ACL/quota/bootstrap acceptance.

Uses generated mTLS/JWS credentials, native app roles/RLS and primary auth/root/billing.
No shared stand or existing database is modified. Cleanup uses the signed harness run state.
"""
import uuid
import time
import json
import base64
import zipfile
from pathlib import Path
import e2e as e
import trusted_rpc_e2e as t
from capabilities_e2e import CapabilitiesLocal

_base_edge = t.TrustedLocal.edge


class ProjectAclLocal(CapabilitiesLocal):
    def __init__(self, root, store, state):
        super().__init__(root, store, state)
        self.recovery_tenant = str(uuid.uuid4())

    def edge(self, issuer, audience, rpc, action, kinds, scopes, purpose='USER', mode='BOUND'):
        _base_edge(self, issuer, audience, rpc, action, kinds, scopes, purpose, mode)
        if purpose != 'USER':
            self.snapshot['edges'][-1]['serviceIdentities'] = [issuer]

    def configure_service(self, name, env, props):
        super().configure_service(name, env, props)
        for service in self.modules:
            props['taska.domain.' + service + '.port'] = '9090'
        if name in ('project-service', 'workflow-service', 'issue-service'):
            short = name.split('-')[0]
            env.update({'SPRING_PROFILES_ACTIVE': 'tenant-storage', 'SPRING_R2DBC_USERNAME': 'taska_' + short + '_app',
                        'SPRING_R2DBC_PASSWORD': self.password, 'SPRING_LIQUIBASE_USER': 'taska_' + short + '_deployer',
                        'SPRING_LIQUIBASE_PASSWORD': self.password})
        if name == 'project-service':
            props['taska.projects.recovery-tenants'] = self.recovery_tenant
            props['taska.projects.recovery-interval'] = '2s'
            props['logging.level.ru.taska.projectbootstrap.ProjectCreationRecovery'] = 'DEBUG'
        if name == 'auth-service':
            env['SPRING_PROFILES_ACTIVE'] = 'tenant-storage'

    def configure_container(self, name, service):
        if name in ('auth-service', 'project-service'):
            service.setdefault('ports', []).append('127.0.0.1::9090')

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        for service in ('project', 'workflow', 'issue'):
            # The parent creates an empty legacy schema in this run's new database.
            # RESTRICT fails if anything was unexpectedly created; never discard tables.
            self.sql(service + '_db', 'DROP SCHEMA taska RESTRICT;')
            self.compose('exec', '-T', 'database', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'taska', '-d', service + '_db',
                         '-v', 'service=' + service, '-v', 'schema=taska', '-f', '/tmp/bootstrap.psql')
            self.sql(service + '_db', "CREATE SCHEMA %s_liquibase AUTHORIZATION taska_%s_migrator;ALTER ROLE taska_%s_app PASSWORD '%s';ALTER ROLE taska_%s_deployer PASSWORD '%s';" % (service, service, service, self.password, service, self.password))
        self.sql('issue_db', 'CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;')
        for issuer in ('project-service', 'workflow-service', 'tenant-service', 'auth-service'):
            self.edge(issuer, 'tenant-service', 'ru.taska.b2b.v1.TenantMembershipService/BatchValidateMemberships',
                      'batchValidateMemberships', ['SERVICE'], ['memberships:read'], 'membership')
        for issuer in ('project-service', 'tenant-service'):
            self.edge(issuer, 'tenant-service', 'ru.taska.b2b.v1.PlatformService/GetPlatformConfigurationSnapshot',
                      'getPlatformConfigurationSnapshot', ['SERVICE'], ['read'], 'admin', 'NONE')
            self.edge(issuer, 'billing-service', 'ru.taska.b2b.v1.EntitlementService/GetEntitlementSnapshot',
                      'getEntitlementSnapshot', ['SERVICE'], ['read'], 'billing')
            self.edge(issuer, 'billing-service', 'ru.taska.b2b.v1.BillingService/ReadReferencedPlanVersion',
                      'readReferencedPlanVersion', ['SERVICE'], ['read', 'catalog:read'], 'billing', 'NONE')
        kinds, scopes = ['WEB_TENANT', 'USER_PAT'], ['read', 'write']
        for issuer in ('project-service', 'workflow-service'):
            for method, action in (('ReserveQuota', 'reserveQuota'), ('GetQuotaReservation', 'getQuotaReservation'),
                                   ('CommitQuota', 'commitQuota'), ('ReleaseQuota', 'releaseQuota')):
                self.edge(issuer, 'tenant-service', 'ru.taska.b2b.v1.QuotaAuthorityService/' + method, action, kinds, scopes)
        for method, action in (('Inspect', 'projectQuotaInspect'), ('Cancel', 'projectQuotaCancel'),
                               ('Open', 'projectQuotaOpen'), ('Inventory', 'projectQuotaInventory')):
            self.edge('tenant-service', 'project-service', 'ru.taska.b2b.v1.ProjectQuotaOwnerService/' + method, action, kinds, scopes)
        for method, action in (('Ensure', 'initializeProjectWorkflow'), ('Cancel', 'cancelProjectWorkflow'), ('Read', 'readProjectWorkflows')):
            self.edge('project-service', 'workflow-service', 'ru.taska.b2b.v1.ProjectWorkflowBootstrapService/' + method, action, kinds, scopes)
        self.edge('workflow-service', 'project-service', 'ru.taska.b2b.v1.ProjectInitializationService/Describe', 'describeProjectInitialization', kinds, scopes)
        self.edge('project-service', 'auth-service', 'ru.taska.b2b.v1.ProjectDirectoryService/Profiles', 'readProjectProfiles', ['SERVICE'], ['memberships:read', 'profiles:read'], 'membership')
        self.edge('project-service', 'issue-service', 'ru.taska.b2b.v1.ProjectLabelsService/Read', 'readProjectLabels', kinds, scopes)
        # Managed recovery preserves the original project-service identity across delegated hops.
        recovery = [('project-service', 'project-service', 'ProjectInitializationService', 'Recover', 'recoverProjectCreation'),
                    ('workflow-service', 'project-service', 'ProjectInitializationService', 'Describe', 'describeProjectInitialization')]
        for issuer in ('project-service', 'workflow-service'):
            for method, action in (('GetQuotaReservation', 'getQuotaReservation'), ('CommitQuota', 'commitQuota'), ('ReleaseQuota', 'releaseQuota')):
                recovery.append((issuer, 'tenant-service', 'QuotaAuthorityService', method, action))
        for method, action in (('Inspect', 'projectQuotaInspect'), ('Cancel', 'projectQuotaCancel'), ('Open', 'projectQuotaOpen'), ('Inventory', 'projectQuotaInventory')):
            recovery.append(('tenant-service', 'project-service', 'ProjectQuotaOwnerService', method, action))
        for method, action in (('Ensure', 'initializeProjectWorkflow'), ('Cancel', 'cancelProjectWorkflow')):
            recovery.append(('project-service', 'workflow-service', 'ProjectWorkflowBootstrapService', method, action))
        for issuer, audience, service, method, action in recovery:
            self.edge(issuer, audience, 'ru.taska.b2b.v1.' + service + '/' + method, action, ['SERVICE'], ['write', 'project:recover'], 'provisioning')
            self.snapshot['edges'][-1]['serviceIdentities'] = ['project-service']
        self.refresh()

    def scenarios(self):
        actor, clients = self.seed()
        for tenant, _ in clients:
            self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
            self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
        first, owner = clients[0]
        second, other = clients[1]
        for _, client in clients:
            t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
        projects = []
        for tenant, client in clients:
            key = str(uuid.uuid4())
            payload = {'projectKey': '_', 'name': 'Owned native project', 'description': 'Description', 'color': '#123456'}
            created = client.expect('POST', '/api/v1/projects', 201, payload, {'Idempotency-Key': key}).json()
            again = client.expect('POST', '/api/v1/projects', 201, payload, {'Idempotency-Key': key}).json()
            e.require(created['id'] == again['id'] and created['currentUserRole'] == 'ADMIN', 'Retry changed project identity or effective role')
            client.expect('POST', '/api/v1/projects', 409, dict(payload, name='Changed'), {'Idempotency-Key': key})
            project = created['id']
            context = client.expect('GET', '/api/v1/projects/' + project + '/context', 200).json()
            e.require(len(context['issueTypes']) == 3 and len(context['workflows']) == 3, 'Project opened before full workflow/type initialization')
            e.require(all(len(item['workflow']['statuses']) == 3 and len(item['workflow']['transitions']) == 6 for item in context['workflows']), 'Project workflow graph is incomplete')
            e.require(created['description'] == 'Description' and created['color'] == '#123456', 'Create lost optional fields')
            quota = self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip()
            e.require(quota == '1/0', 'Retry double-counted or leaked project quota')
            e.require(self.sql('project_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND aggregate_id='%s' AND event_type='ProjectCreated';" % (tenant, project)).strip() == '1', 'Creation domain event was missing or duplicated')
            projects.append(project)
        project, foreign = projects
        # A different operation with the same key must compensate its own reservation only.
        owner.expect('POST', '/api/v1/projects', 422, {'projectKey': '_', 'name': 'Duplicate'})
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % first).strip() == '1/0', 'Rejected admission leaked quota')
        owner.expect('GET', '/api/v1/projects/' + foreign, 404)
        owner.expect('PATCH', '/api/v1/projects/' + foreign, 404, {'name': 'Foreign'})
        owner.expect('GET', '/api/v1/projects/' + foreign + '/context', 404)
        # No explicit grant: current company admin still has ADMIN on every company project.
        self.sql('project_db', "DELETE FROM taska.project_members WHERE tenant_id='%s' AND project_id='%s' AND user_id='%s';" % (first, project, actor))
        e.require(owner.expect('GET', '/api/v1/projects/' + project, 200).json()['currentUserRole'] == 'ADMIN', 'Tenant admin depends on explicit grant')
        listed = owner.expect('GET', '/api/v1/projects', 200).json()
        e.require(project in str(listed) and foreign not in str(listed), 'Project collection leaked or lost company scope')
        member = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','project-member@harness.invalid','Owned project member','ACTIVE','USER');" % member)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (first, member))
        normal, _ = self.member_client(first, member, 1)
        normal.expect('GET', '/api/v1/projects/' + project, 404)
        normal.expect('POST', '/api/v1/projects', 403, {'projectKey': 'DENIED', 'name': 'Denied'})
        owner.expect('POST', '/api/v1/projects/' + project + '/members', 201, {'userId': member, 'role': 'VIEWER'})
        e.require(normal.expect('GET', '/api/v1/projects/' + project, 200).json()['currentUserRole'] == 'VIEWER', 'Current explicit role missing')
        normal.expect('PATCH', '/api/v1/projects/' + project, 403, {'name': 'Denied'})
        foreign_user = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','foreign@harness.invalid','Foreign fixture','ACTIVE','USER');" % foreign_user)
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (second, foreign_user))
        owner.expect('POST', '/api/v1/projects/' + project + '/members', 404, {'userId': foreign_user, 'role': 'MEMBER'})
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (first, member))
        e.require(normal.request('GET', '/api/v1/projects/' + project).status in (401, 403), 'Suspended membership retained access')
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET status='ACTIVE',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (first, member))
        fresh, _ = self.member_client(first, member, 3)
        fresh.expect('GET', '/api/v1/projects/' + project, 404)
        owner.expect('POST', '/api/v1/projects/' + project + '/members', 201, {'userId': member, 'role': 'MEMBER'})
        fresh.expect('GET', '/api/v1/projects/' + project, 200)
        owner.expect('PATCH', '/api/v1/projects/' + project, 200, {'name': 'Updated', 'description': None, 'color': None})
        reader, _ = self.member_client(first, actor, 1, ['read'])
        reader.expect('POST', '/api/v1/projects', 403, {'projectKey': 'READ_DENIED', 'name': 'Denied'})
        probe = self.direct_probe(owner, actor, first, project, foreign)
        self.role_replay_scenarios(actor)
        self.recovery_scenarios(actor)
        return {'nativeProjectCrudAcl': 'PASS', 'tenantAdminImplicit': 'PASS', 'foreignTenantAndTargets': 'PASS',
                'membershipGenerationFence': 'PASS', 'idempotentQuota': 'PASS', 'fullWorkflowBeforeOpen': 'PASS', 'failureCompensation': 'PASS',
                'unknownOutcomeRecovery': 'PASS', 'acceptedFlagPinAndRevokedInitiator': 'PASS', 'currentRoleOnCreateReplay': 'PASS', 'primaryAdministrativeMfaAndAudit': 'PASS', **probe}

    def direct_probe(self, client, actor, tenant, project, foreign):
        claims = json.loads(base64.urlsafe_b64decode(client.token.split('.')[1] + '=='))
        libraries = self.material / 'probe-libs'
        libraries.mkdir(mode=0o700)
        with zipfile.ZipFile(self.dir / 'jars/project-service.jar') as archive:
            for item in archive.infolist():
                if item.filename.startswith('BOOT-INF/lib/') and item.filename.endswith('.jar'):
                    (libraries / Path(item.filename).name).write_bytes(archive.read(item))
        parameters = self.material / 'project-probe.json'
        e.private_json(parameters, {'material': str(self.material), 'actor': actor, 'tenant': tenant, 'credential': claims['sid'],
                                   'project': project, 'foreignProject': foreign,
                                   'authPort': int(self.compose('port', 'auth-service', '9090').strip().rsplit(':', 1)[1]),
                                   'projectPort': int(self.compose('port', 'project-service', '9090').strip().rsplit(':', 1)[1])})
        output = self.command('java', '--class-path', str(libraries / '*'), str(self.root / 'scripts/harness/ProjectAclProbe.java'), str(parameters))
        return json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{')))

    def role_replay_scenarios(self, owner):
        tenant, creator = str(uuid.uuid4()), str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','project-creator@harness.invalid','Project creator fixture','ACTIVE','USER');" % creator)
        self.sql('tenant_db', "BEGIN;INSERT INTO taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id) VALUES('%s','Role fixture','%s','ACTIVE','FREE','%s','%s',decode('00','hex'),'%s');INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE'),('%s','%s','TENANT_ADMIN','ACTIVE');COMMIT;" % (tenant, owner, str(uuid.uuid4()), owner, str(uuid.uuid4()), tenant, owner, tenant, creator))
        self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
        self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
        client, _ = self.member_client(tenant, creator, 1)
        payload, key = {'projectKey': 'ROLE', 'name': 'Role fixture'}, str(uuid.uuid4())
        project = client.expect('POST', '/api/v1/projects', 201, payload, {'Idempotency-Key': key}).json()['id']
        self.sql('tenant_db', "UPDATE taska.tenant_memberships SET role='MEMBER',version=version+1 WHERE tenant_id='%s' AND user_id='%s';" % (tenant, creator))
        fresh, _ = self.member_client(tenant, creator, 2)
        fresh.expect('GET', '/api/v1/projects/' + project, 404)
        fresh.expect('POST', '/api/v1/projects', 403, payload, {'Idempotency-Key': key})
        admin, _ = self.member_client(tenant, owner, 1)
        admin.expect('POST', '/api/v1/projects/' + project + '/members', 201, {'userId': creator, 'role': 'ADMIN'})
        fresh.expect('PATCH', '/api/v1/projects/' + project, 200, {'name': 'Current project admin'})
        fresh.expect('POST', '/api/v1/projects', 403, payload, {'Idempotency-Key': key})
        fresh.expect('POST', '/api/v1/projects', 403, {'projectKey': 'NEW_ROLE_DENIED', 'name': 'Denied'})
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '1/0', 'Role denial changed project quota')
        # Account role alone is insufficient; the actual web session must have administrative MFA.
        self.sql('auth_db', "UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id='%s';" % creator)
        fresh.expect('GET', '/api/v1/projects/' + project, 403)
        claims = json.loads(base64.urlsafe_b64decode(fresh.token.split('.')[1] + '=='))
        self.sql('auth_db', "UPDATE taska.web_sessions SET assurance='ADMIN_EMAIL_MFA' WHERE id=(SELECT web_session_id FROM taska.tenant_sessions WHERE id='%s');" % claims['sid'])
        fresh.expect('POST', '/api/v1/projects', 201, {'projectKey': 'GA', 'name': 'Audited administrative fixture'})
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '2/0', 'Verified administrative exception was not applied')
        e.require(int(self.sql('project_db', "SELECT count(*) FROM taska.project_authorization_audit WHERE tenant_id='%s' AND actor_id='%s' AND action='project.create' AND decision->>'businessBypass'='true';" % (tenant, creator)).strip()) > 0, 'Administrative creation was not audited')


    def recovery_scenarios(self, actor):
        tenant = self.recovery_tenant
        self.sql('tenant_db', "BEGIN;INSERT INTO taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id) VALUES('%s','Recovery fixture','%s','ACTIVE','FREE','%s','%s',decode('00','hex'),'%s');INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');COMMIT;" % (tenant, actor, str(uuid.uuid4()), actor, str(uuid.uuid4()), tenant, actor))
        self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % tenant)
        client, credential = self.member_client(tenant, actor, 1)
        t.wait_protected_read(client, '/api/v1/projects/' + str(uuid.uuid4()), 404)
        payload, failed_key = {'projectKey': 'RECOVER', 'name': 'Recovery fixture'}, str(uuid.uuid4())
        # Missing company copy is a confirmed rejection, not a successful empty initialization.
        client.expect('POST', '/api/v1/projects', 409, payload, {'Idempotency-Key': failed_key})
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '0/0':
                break
            time.sleep(0.5)
        e.require(self.sql('project_db', "SELECT count(*) FROM taska.projects WHERE tenant_id='%s';" % tenant).strip() == '0', 'Confirmed rejection retained a hidden project')
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '0/0', 'Confirmed rejection retained quota')
        e.require(self.sql('workflow_db', "SELECT count(*) FROM taska.project_initializations WHERE tenant_id='%s' AND state='CANCELLED';" % tenant).strip() == '1', 'Cancellation tombstone missing')
        self.sql('workflow_db', "INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph) SELECT '%s',id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates WHERE code='DEFAULT';" % tenant)
        client.expect('POST', '/api/v1/projects', 409, payload, {'Idempotency-Key': failed_key})
        self.compose('stop', 'workflow-service')
        pending_key = str(uuid.uuid4())
        client.expect('POST', '/api/v1/projects', 503, payload, {'Idempotency-Key': pending_key})
        pending = self.sql('project_db', "SELECT project_id FROM taska.project_creation_operations WHERE tenant_id='%s' AND state='CREATING';" % tenant).strip()
        e.require(bool(pending), 'Unknown outcome lost its original resource identity')
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '0/1', 'Unknown outcome released a potentially committed slot')
        e.require(not client.expect('GET', '/api/v1/projects', 200).json()['items'], 'Partially initialized project became visible')
        observer = str(uuid.uuid4())
        self.sql('auth_db', "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('%s','recovery-observer@harness.invalid','Recovery observer','ACTIVE','USER');UPDATE taska.tenant_sessions SET revoked_at=now() WHERE id='%s';" % (observer, credential))
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','TENANT_ADMIN','ACTIVE');INSERT INTO taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at) SELECT key,2,value_type,'false'::jsonb,now(),'%s',now() FROM taska.platform_configuration_definitions WHERE key='PROJECT_CREATION';" % (tenant, observer, actor))
        watcher, _ = self.member_client(tenant, observer, 1)
        watcher.expect('POST', '/api/v1/projects', 403, {'projectKey': 'FLAG_DENIED', 'name': 'Denied'})
        e.require(client.request('GET', '/api/v1/projects/' + pending).status in (401, 403), 'Revoked initiator credential retained access')
        self.compose('start', 'workflow-service')
        self.wait_ready('http://' + self.compose('port', 'workflow-service', '8080').strip())
        deadline, opened = time.monotonic() + 35, False
        while time.monotonic() < deadline:
            result = watcher.request('GET', '/api/v1/projects/' + pending)
            if result.status == 200:
                opened = True
                break
            e.require(result.status in (404, 503), 'Recovery returned unexpected status')
            time.sleep(1)
        e.require(opened, 'Scoped worker failed to finish the original operation')
        e.require(self.sql('tenant_db', "SELECT used||'/'||reserved FROM taska.quota_counters WHERE tenant_id='%s' AND resource='PROJECT';" % tenant).strip() == '1/0', 'Recovery changed quota identity or count')
        e.require(self.sql('project_db', "SELECT count(*) FROM taska.projects WHERE tenant_id='%s';" % tenant).strip() == '1', 'Recovery created a new resource')
        e.require(self.sql('project_db', "SELECT count(*) FROM taska.outbox_events WHERE tenant_id='%s' AND event_type='ProjectCreated';" % tenant).strip() == '1', 'Recovery duplicated the creation event')
        watcher.expect('GET', '/api/v1/projects/' + pending + '/context', 200)


def main():
    t.TrustedLocal = ProjectAclLocal
    t.main()


if __name__ == '__main__':
    main()
