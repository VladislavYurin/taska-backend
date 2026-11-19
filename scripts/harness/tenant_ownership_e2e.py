"""TAS-473: собственный стенд REST/auth/Root/Billing, без реальных платежей PSP."""
import hashlib
import secrets
import time
import json
import uuid

import e2e as e
import trusted_rpc_e2e as t
from capabilities_e2e import CapabilitiesLocal
from tenant_rename_e2e import TenantRenameLocal


class TenantOwnershipLocal(CapabilitiesLocal):
    modules = ('auth-service', 'api-gateway', 'tenant-service', 'billing-service')
    identities = t.IDENTITIES + ('billing-service',)
    user = TenantRenameLocal.user
    web_client = TenantRenameLocal.web_client
    headers = staticmethod(TenantRenameLocal.headers)

    def configure_service(self, name, env, props):
        super().configure_service(name, env, props)
        for service in self.modules:
            props['taska.domain.' + service + '.port'] = '9090'

    def prepare_extra_databases(self):
        super().prepare_extra_databases()
        TenantRenameLocal.prepare_extra_databases(self)
        self.edge('api-gateway', 'tenant-service', 'ru.taska.b2b.v1.TenantService/TransferTenantOwnership',
                  'transferTenantOwnership', ['WEB_TENANT', 'USER_PAT', 'WEB_GLOBAL'], ['read', 'write'])
        for issuer, audience, service, method, action, scope, mode in (
                ('tenant-service', 'billing-service', 'BillingOwnershipTransferService', 'Prepare', 'prepareOwnershipTransfer', 'ownership:prepare', 'BOUND'),
                ('tenant-service', 'billing-service', 'BillingOwnershipTransferService', 'Finish', 'finishOwnershipTransfer', 'ownership:finish', 'BOUND'),
                ('tenant-service', 'tenant-service', 'TenantOwnershipTransferService', 'Recover', 'recoverOwnershipTransfer', 'ownership:recover', 'BOUND'),
                ('billing-service', 'tenant-service', 'TenantOwnershipTransferService', 'Inspect', 'inspectOwnershipTransfer', 'ownership:inspect', 'BOUND')):
            read = method == 'Inspect'
            self.edge(issuer, audience, 'ru.taska.b2b.v1.' + service + '/' + method, action,
                      ['SERVICE'], ['read' if read else 'write', scope], 'billing', mode)
            self.snapshot['edges'][-1]['serviceIdentities'] = [issuer]
        self.refresh()

    def pat_client(self, actor, tenant):
        secret = 'tsk_pat_' + secrets.token_urlsafe(32)
        token, grant = str(uuid.uuid4()), str(uuid.uuid4())
        digest = hashlib.sha256(secret.encode()).hexdigest()
        self.sql('auth_db', "INSERT INTO taska.integration_grants(id,user_id,kind,scope_type,tenant_id,access_level,permissions,version,created_at,expires_at) VALUES('%s','%s','USER','ONE_TENANT','%s','MODIFY','{}',1,now(),now()+interval '1 hour');INSERT INTO taska.personal_access_tokens(id,user_id,name,token_prefix,token_hash,scopes,created_at,expires_at,integration_grant_id) VALUES('%s','%s','owned-transfer','tsk_pat_test','%s',ARRAY['read','write'],now(),now()+interval '1 hour','%s');" % (grant, actor, tenant, token, actor, digest, grant))
        return e.Http(self.state['origin'], secret)

    def paid(self, tenant):
        invoice, period, operation, fact = [str(uuid.uuid4()) for _ in range(4)]
        plan = '00000000-0000-8000-8000-000000000002'
        self.sql('billing_db', f"""
          BEGIN;
          INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode)
            SELECT '{tenant}',id,to_jsonb(p),'PAID' FROM taska.catalog_plan_versions p WHERE id='{plan}';
          INSERT INTO taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,
            currency,currency_exponent,subtotal_minor,tax_minor,total_minor,issued_at,payable_until,
            terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot)
          VALUES('{invoice}','{tenant}','OWNED-{invoice}','{operation}','{invoice}','INITIAL','{plan}',
            'PSP','EUR',2,2000,0,2000,now(),now()+interval '7 days','{{}}','{{}}','{{}}','{{}}','');
          INSERT INTO taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot)
            VALUES('{tenant}','{invoice}',1,'Синтетическое оплаченное покрытие',1,2000,0,2000,'[]','');
          UPDATE taska.invoices SET state='OPEN' WHERE id='{invoice}';
          INSERT INTO taska.subscription_periods(id,tenant_id,invoice_id,source_financial_fact_id,plan_version_id,
            starts_at,ends_at,anchor_day,anchor_time_utc,activation_operation_id,terms_snapshot,confirmed_at,payment_source)
          SELECT '{period}','{tenant}','{invoice}','{fact}',id,now()-interval '1 hour',now()+interval '1 day',
            19,'12:00','{operation}',to_jsonb(p),now(),'PSP' FROM taska.catalog_plan_versions p WHERE id='{plan}';
          UPDATE taska.subscriptions SET current_period_id='{period}' WHERE tenant_id='{tenant}';
          COMMIT;
        """)

    def consent(self, tenant, owner):
        method, mandate = str(uuid.uuid4()), str(uuid.uuid4())
        self.sql('billing_db', f"""
          BEGIN;
          INSERT INTO taska.payment_methods(id,tenant_id,owner_id,provider,provider_customer_ref,provider_method_ref,status,created_at)
            VALUES('{method}','{tenant}','{owner}','OWNED','customer','method','ACTIVE',now());
          INSERT INTO taska.autopay_mandates(id,tenant_id,owner_id,owner_version,payment_method_id,provider,merchant_account,
            provider_environment,merchant_profile_version,state,consent_version,created_subscription_version,consented_at,
            revocation_barrier,consent_terms_version,consent_text,configuration_version,consent_channel,consent_snapshot)
          VALUES('{mandate}','{tenant}','{owner}',1,'{method}','OWNED','owned','TEST','1','ACTIVE',1,1,now(),
            'owned','1','Синтетическое согласие','1','WEB','{{}}');
          UPDATE taska.subscriptions SET autopay_state='CONSENTED' WHERE tenant_id='{tenant}';
          COMMIT;
        """)

    def scenarios(self):
        owner_id, clients = self.seed()
        tenant, owner = clients[0]
        free_tenant, free_owner = clients[1]
        owner.timeout = 20
        self.paid(tenant)
        self.consent(tenant, owner_id)
        self.sql('billing_db', "INSERT INTO taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode) SELECT '%s',id,to_jsonb(p),'FREE' FROM taska.catalog_plan_versions p WHERE id='00000000-0000-8000-8000-000000000001';" % free_tenant)
        target = self.user('ownership-target@harness.invalid')
        for company in (tenant, free_tenant):
            self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (company, target))
        path = '/api/v1/tenants/' + tenant
        transfer = path + '/ownership-transfer'
        t.wait_protected_read(owner, path + '/capabilities', 200)
        ordinary = self.web_client(target, tenant)
        payload = {'newOwnerUserId': target, 'confirmed': True}
        original = self.headers('1')
        owner.expect('POST', transfer, 428, payload, {'Idempotency-Key': str(uuid.uuid4())})
        owner.expect('POST', transfer, 412, payload, self.headers('99'))
        ordinary.expect('POST', transfer, 403, payload, original)
        self.pat_client(target, tenant).expect('POST', transfer, 401, payload, original)
        free_owner.expect('POST', '/api/v1/tenants/' + free_tenant + '/ownership-transfer', 403, payload, self.headers('1'))
        owner.expect('POST', transfer, 400, {'newOwnerUserId': target, 'confirmed': False}, original)
        owner.expect('POST', transfer, 413, {'newOwnerUserId': target, 'confirmed': True, 'padding': 'x' * 20000}, original)
        before = self.sql('billing_db', "SELECT jsonb_build_object('periods',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM taska.subscription_periods p),'invoices',(SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM taska.invoices i),'attempts',(SELECT count(*) FROM taska.payment_attempts));").strip()
        changed = owner.expect('POST', transfer, 200, payload, original).json()
        e.require(changed['ownerId'] == target and changed['ownerVersion'] == '2', 'Не сменились owner и owner_version')
        e.require(owner.expect('POST', transfer, 200, payload, original).json() == changed, 'Replay изменил receipt')
        owner.expect('POST', transfer, 409, {'newOwnerUserId': owner_id, 'confirmed': True}, original)
        owner.expect('POST', transfer, 403, {'newOwnerUserId': owner_id, 'confirmed': True}, self.headers(changed['version']))
        current = self.web_client(target, tenant, 2)
        e.require(current.expect('GET', path, 200).json()['membership']['isOwner'], 'Получатель не стал владельцем')
        e.require(not owner.expect('GET', path, 200).json()['membership']['isOwner'], 'Прежний владелец сохранил ownership')
        audit = self.sql('tenant_db', "SELECT count(*) FROM taska.tenant_ownership_audit WHERE tenant_id='%s';" % tenant).strip()
        revoked = self.sql('billing_db', "SELECT count(*) FROM taska.autopay_mandates WHERE tenant_id='%s' AND state='REVOKED' AND revocation_reason='OWNER_TRANSFERRED';" % tenant).strip()
        e.require(audit == '1' and revoked == '1', 'Audit или отзыв согласия не были однократными')
        after = self.sql('billing_db', "SELECT jsonb_build_object('periods',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM taska.subscription_periods p),'invoices',(SELECT jsonb_agg(to_jsonb(i) ORDER BY id) FROM taska.invoices i),'attempts',(SELECT count(*) FROM taska.payment_attempts));").strip()
        e.require(before == after, 'Передача изменила оплаченные факты или создала PSP попытку')
        current.timeout = 20
        returned = current.expect('POST', transfer, 200, {'newOwnerUserId': owner_id, 'confirmed': True}, self.headers(changed['version'])).json()
        e.require(returned['ownerId'] == owner_id and returned['ownerVersion'] == '3', 'Владелец не смог вернуть владение')
        admin_id = self.user('ownership-admin@harness.invalid', 'GLOBAL_ADMIN')
        admin = self.web_client(admin_id, assurance='ADMIN_EMAIL_MFA')
        admin.timeout = 20
        admin_changed = admin.expect('POST', '/api/v1/tenants/' + free_tenant + '/ownership-transfer', 200,
                                     payload, self.headers('1')).json()
        e.require(admin_changed['ownerId'] == target, 'GLOBAL_ADMIN MFA потерял бизнес-исключение B02')
        self.sql('tenant_db', "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('%s','%s','MEMBER','ACTIVE');" % (free_tenant, admin_id))
        tenant_admin = self.web_client(admin_id, free_tenant, assurance='ADMIN_EMAIL_MFA')
        tenant_admin.timeout = 20
        tenant_admin_changed = tenant_admin.expect('POST', '/api/v1/tenants/' + free_tenant + '/ownership-transfer', 200,
            {'newOwnerUserId': owner_id, 'confirmed': True}, self.headers(admin_changed['version'])).json()
        e.require(tenant_admin_changed['ownerId'] == owner_id, 'Tenant web MFA потерял бизнес-исключение B02')

        # Ошибка SQL после финансового Prepare: worker отменяет PENDING, не передаёт владение.
        failed_key = str(uuid.uuid4())
        self.sql('tenant_db', "CREATE FUNCTION taska.owned_transfer_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'OWNED_TRANSFER_FAILURE'; END $$;CREATE TRIGGER owned_transfer_failure BEFORE INSERT ON taska.tenant_ownership_audit FOR EACH ROW EXECUTE FUNCTION taska.owned_transfer_failure();")
        try:
            owner.expect('POST', transfer, 500, payload, self.headers(returned['version'], failed_key))
        finally:
            self.sql('tenant_db', 'DROP TRIGGER owned_transfer_failure ON taska.tenant_ownership_audit;DROP FUNCTION taska.owned_transfer_failure();')
        deadline = time.monotonic() + 50
        recovered = False
        while time.monotonic() < deadline:
            outcome = self.sql('tenant_db', "SELECT state FROM taska.tenant_ownership_transfers WHERE tenant_id='%s' AND request_key='%s';" % (tenant, failed_key)).strip()
            pending = self.sql('billing_db', "SELECT ownership_transfer_id IS NULL FROM taska.subscriptions WHERE tenant_id='%s';" % tenant).strip()
            if outcome == 'CANCELLED' and pending == 't':
                recovered = True
                break
            time.sleep(2)
        e.require(recovered, 'Recovery не отменил PENDING и не снял финансовый fence')
        e.require(owner.expect('GET', path, 200).json()['ownerId'] == owner_id, 'Worker применил неуспешную передачу')
        return {'ownershipRest': 'PASS', 'ownerAndActiveMembership': 'PASS', 'freeAndConfirmationDenied': 'PASS',
                'exactReplay': 'PASS', 'disabledPatFailsClosed': 'PASS', 'adminMfaBypass': 'PASS', 'oldConsentRevoked': 'PASS', 'financialHistoryPreserved': 'PASS', 'pendingRecovery': 'PASS'}


if __name__ == '__main__':
    t.TrustedLocal = TenantOwnershipLocal
    t.main()
