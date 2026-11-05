package ru.taska.billing;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;

/** Synthetic invoices solely for the pre-existing settlement-model tests' evidence adapter. */
final class InvoiceTestFixtures {
  static void ensure(DatabaseClient operator, UUID tenant, UUID invoice, UUID plan) {
    ensure(operator, tenant, invoice, plan, UUID.randomUUID(), "INITIAL");
  }

  static void ensure(
      DatabaseClient operator,
      UUID tenant,
      UUID invoice,
      UUID plan,
      UUID operation,
      String purpose) {
    operator
        .sql(
            """
            DO $$ BEGIN
              IF EXISTS(SELECT 1 FROM taska.subscriptions WHERE tenant_id='%s')
                 AND NOT EXISTS(SELECT 1 FROM taska.invoices WHERE tenant_id='%s' AND id='%s') THEN
                INSERT INTO taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,
                  currency,currency_exponent,subtotal_minor,tax_minor,total_minor,issued_at,payable_until,
                  terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot)
                VALUES('%s','%s','TEST-%s','%s','%s','%s','%s','PSP','EUR',2,2000,0,2000,
                  now(),now()+interval '7 days','{}','{}','{}','{}','');
                INSERT INTO taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,
                  total_minor,taxes_snapshot,snapshot) VALUES('%s','%s',1,'Test coverage evidence',1,2000,0,2000,'[]','');
                UPDATE taska.invoices SET state='OPEN' WHERE tenant_id='%s' AND id='%s';
              END IF;
            END $$
            """
                .formatted(
                    tenant, tenant, invoice, invoice, tenant, invoice, operation, invoice, purpose,
                    plan, tenant, invoice, tenant, invoice))
        .fetch()
        .rowsUpdated()
        .block(SubscriptionIntegrationTest.WAIT);
  }
}
