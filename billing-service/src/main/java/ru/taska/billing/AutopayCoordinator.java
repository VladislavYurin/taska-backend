package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.billing.AutopayDependencies.Grant;
import ru.taska.billing.AutopayDependencies.Use;
import ru.taska.billing.payment.BillingOperationStore;
import ru.taska.billing.payment.PaymentAttemptCoordinator;
import ru.taska.billing.payment.PaymentAttemptStore;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.billing.payment.ProviderException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Приём попытки сериализуется с cancel/consent; сетевой эффект начинается только после commit. */
public final class AutopayCoordinator {
  private final AutopayStore store;
  private final PaymentAttemptStore attempts;
  private final BillingOperationStore operations;
  private final PaymentAttemptCoordinator observer;

  public AutopayCoordinator(
      AutopayStore store,
      PaymentAttemptStore attempts,
      BillingOperationStore operations,
      PaymentAttemptCoordinator.Mapping mapping) {
    this.store = store;
    this.attempts = attempts;
    this.operations = operations;
    this.observer = new PaymentAttemptCoordinator(attempts, store.providers, operations, mapping);
  }

  private static final class ConsentChanged extends RuntimeException {
    final UUID mandate;

    ConsentChanged(UUID mandate) {
      super("AUTOPAY_CONSENT_REVOKED");
      this.mandate = mandate;
    }
  }

  private record Admission(
      PaymentAttemptStore.Attempt attempt,
      PaymentProvider provider,
      PaymentProvider.SavedMethod method,
      boolean submit) {}

  public Mono<PaymentAttemptStore.Attempt> dispatch(UUID tenant, UUID invoice) {
    return worker(tenant)
        .flatMap(
            c ->
                store.transactions.execute(
                    db ->
                        store
                            .subscriptions
                            .lock(db, tenant)
                            .flatMap(
                                subscription ->
                                    existing(db, tenant, invoice)
                                        .switchIfEmpty(
                                            Mono.defer(
                                                () ->
                                                    admit(db, c, tenant, invoice, subscription))))))
        .flatMap(a -> a.submit() ? submit(a) : observer.get(tenant, a.attempt().id()))
        .onErrorResume(
            ConsentChanged.class,
            e ->
                store
                    .invalidate(tenant, e.mandate)
                    .then(
                        Mono.error(
                            fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_CONSENT_REVOKED"))));
  }

  private Mono<VerifiedTenantContext> worker(UUID tenant) {
    return VerifiedTenantContext.current()
        .map(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (!tenant.toString().equals(c.tenantId())
                  || (!c.scopes().contains("write") || !c.scopes().contains("payment:recurring"))
                  || !Set.of("dispatchAutopay", "issueRenewalInvoice").contains(c.action())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService()))
                throw fail(DomainStatus.PERMISSION_DENIED, "SERVICE_IDENTITY_REQUIRED");
              return c;
            });
  }

  private Mono<Admission> existing(DatabaseClient db, UUID tenant, UUID invoice) {
    return db.sql(
            "SELECT attempt_id FROM taska.autopay_dispatches WHERE tenant_id=:t AND invoice_id=:i")
        .bind("t", tenant)
        .bind("i", invoice)
        .map((r, m) -> r.get("attempt_id", UUID.class))
        .one()
        .flatMap(
            id ->
                attempts
                    .find(tenant, id)
                    .switchIfEmpty(
                        Mono.error(fail(DomainStatus.DATA_LOSS, "AUTOPAY_ATTEMPT_MISSING"))))
        .map(a -> new Admission(a, null, null, false));
  }

  private Mono<Admission> admit(
      DatabaseClient db,
      VerifiedTenantContext context,
      UUID tenant,
      UUID invoice,
      Map<String, Object> subscription) {
    if (!"PAID".equals(subscription.get("mode"))
        || !"CONSENTED".equals(subscription.get("autopay_state"))
        || !Boolean.TRUE.equals(subscription.get("renewal_enabled"))
        || Boolean.TRUE.equals(subscription.get("renewal_cancelled")))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_DISABLED"));
    return db.sql(
            "SELECT *,consent_snapshot::text AS snapshot_document FROM taska.autopay_mandates WHERE"
                + " tenant_id=:t AND state='ACTIVE'")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(
            Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_CONSENT_REQUIRED")))
        .flatMap(
            mandate ->
                store
                    .current(context, Use.DISPATCH, (UUID) mandate.get("payment_method_id"))
                    .flatMap(
                        grant -> {
                          matching(grant, mandate);
                          return store
                              .ready(grant)
                              .flatMap(
                                  provider ->
                                      invoice(db, tenant, invoice)
                                          .flatMap(
                                              document ->
                                                  validateInvoice(
                                                          db, tenant, subscription, document)
                                                      .then(
                                                          store.method(
                                                              db,
                                                              tenant,
                                                              (UUID)
                                                                  mandate.get("payment_method_id")))
                                                      .flatMap(
                                                          method -> {
                                                            if (!"ACTIVE"
                                                                    .equals(method.get("status"))
                                                                || !grant
                                                                    .owner()
                                                                    .equals(method.get("owner_id"))
                                                                || !grant
                                                                    .method()
                                                                    .provider()
                                                                    .equals(method.get("provider")))
                                                              return Mono.error(
                                                                  fail(
                                                                      DomainStatus
                                                                          .FAILED_PRECONDITION,
                                                                      "PAYMENT_METHOD_REVOKED"));
                                                            return accept(
                                                                db,
                                                                context,
                                                                tenant,
                                                                document,
                                                                mandate,
                                                                grant,
                                                                provider,
                                                                new PaymentProvider.SavedMethod(
                                                                    (String)
                                                                        method.get(
                                                                            "provider_customer_ref"),
                                                                    (String)
                                                                        method.get(
                                                                            "provider_method_ref")));
                                                          })));
                        }));
  }

  private static void matching(Grant g, Map<String, Object> mandate) {
    if (!g.owner().equals(mandate.get("owner_id"))
        || !Long.valueOf(g.ownerVersion()).equals(mandate.get("owner_version"))
        || !g.revocationBarrier().equals(mandate.get("revocation_barrier")))
      throw new ConsentChanged((UUID) mandate.get("id"));
    var b = g.method();
    if (b == null
        || !b.provider().equals(mandate.get("provider"))
        || !b.merchantAccount().equals(mandate.get("merchant_account"))
        || !b.environment().name().equals(mandate.get("provider_environment")))
      throw fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_PROVIDER_CHANGED");
  }

  private Mono<Map<String, Object>> invoice(DatabaseClient db, UUID tenant, UUID invoice) {
    return db.sql(
            """
SELECT i.*,a.source_period_id,p.ends_at AS source_ends,p.starts_at AS source_starts,p.status AS source_status,
 i.pricing_snapshot->>'profileVersion' AS profile_version
FROM taska.invoices i JOIN taska.renewal_calendar_assignments a ON a.tenant_id=i.tenant_id AND a.id=i.operation_id
 JOIN taska.subscription_periods p ON p.tenant_id=a.tenant_id AND p.id=a.source_period_id
WHERE i.tenant_id=:t AND i.id=:i AND i.purpose='RENEWAL' AND i.method='PSP'
""")
        .bind("t", tenant)
        .bind("i", invoice)
        .fetch()
        .one()
        .switchIfEmpty(
            Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RENEWAL_INVOICE_REQUIRED")));
  }

  private Mono<Void> validateInvoice(
      DatabaseClient db, UUID tenant, Map<String, Object> sub, Map<String, Object> invoice) {
    var now = store.clock.instant();
    if (!"OPEN".equals(invoice.get("state"))
        || !"GRANTED".equals(invoice.get("source_status"))
        || !invoice.get("source_period_id").equals(sub.get("current_period_id"))
        || !invoice.get("selection_generation").equals(sub.get("selection_generation"))
        || !now.isBefore(((OffsetDateTime) invoice.get("source_ends")).toInstant())
        || now.isBefore(((OffsetDateTime) invoice.get("source_starts")).toInstant())
        || !now.isBefore(((OffsetDateTime) invoice.get("payable_until")).toInstant()))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RENEWAL_NOT_PAYABLE"));
    return db.sql(
            "SELECT EXISTS(SELECT 1 FROM taska.subscription_plan_changes WHERE tenant_id=:t AND"
                + " state='PREPARED') AS pending")
        .bind("t", tenant)
        .map((r, m) -> r.get("pending", Boolean.class))
        .one()
        .flatMap(
            pending ->
                pending
                    ? Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "PLAN_CHANGE_PENDING"))
                    : Mono.empty());
  }

  private Mono<Admission> accept(
      DatabaseClient db,
      VerifiedTenantContext context,
      UUID tenant,
      Map<String, Object> invoice,
      Map<String, Object> mandate,
      Grant grant,
      PaymentProvider provider,
      PaymentProvider.SavedMethod method) {
    UUID invoiceId = (UUID) invoice.get("id"), mandateId = (UUID) mandate.get("id");
    String profile = (String) invoice.get("profile_version");
    if (profile == null || profile.isBlank())
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "SEALED_PROFILE_REQUIRED"));
    // Только исходный провайдер и одобренный профиль запечатанного счёта.
    if (store.providers.require(grant.method().provider(), grant.method().merchant(), profile)
        != provider)
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_PROVIDER_CHANGED"));
    String key = "autopay:" + invoiceId;
    String snapshot =
        "{\"mandateId\":\"" + mandateId + "\",\"ownerVersion\":\"" + grant.ownerVersion() + "\"}";
    var request =
        new PaymentAttemptStore.Request(
            tenant,
            invoiceId,
            grant.method().provider(),
            key,
            grant.method().merchantAccount(),
            grant.method().environment(),
            snapshot);
    var money =
        new PaymentProvider.Invoice(
            invoiceId,
            new PaymentProvider.Money(
                ((Number) invoice.get("total_minor")).longValue(),
                (String) invoice.get("currency")));
    return operations
        .accept(
            new BillingOperationStore.Request(
                tenant,
                "AUTOPAY",
                key,
                AutopayStore.digest(invoiceId + ":" + mandateId + ":" + grant.ownerVersion()),
                "PSP",
                null,
                null))
        .flatMap(operation -> attempts.accept(request, operation.id(), money))
        .flatMap(
            attempt ->
                store
                    .current(context, Use.DISPATCH, (UUID) mandate.get("payment_method_id"))
                    .flatMap(
                        current -> {
                          matching(current, mandate);
                          if (!current.admissionAllowed())
                            return Mono.error(
                                fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_UNAVAILABLE"));
                          return store
                              .ready(current)
                              .flatMap(
                                  checked -> {
                                    if (checked != provider)
                                      return Mono.error(
                                          fail(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "AUTOPAY_PROVIDER_CHANGED"));
                                    return finalClaim(context, mandate, provider, attempt);
                                  });
                        }))
        .flatMap(
            claim ->
                db.sql(
                        """
INSERT INTO taska.autopay_dispatches(tenant_id,invoice_id,mandate_id,attempt_id,source_period_id,selection_generation,accepted_at)
VALUES(:t,:i,:m,:a,:source,:generation,:now)
""")
                    .bind("t", tenant)
                    .bind("i", invoiceId)
                    .bind("m", mandateId)
                    .bind("a", claim.id())
                    .bind("source", invoice.get("source_period_id"))
                    .bind("generation", invoice.get("selection_generation"))
                    .bind("now", store.now())
                    .fetch()
                    .rowsUpdated()
                    .thenReturn(new Admission(claim, provider, method, true)));
  }

  private Mono<PaymentAttemptStore.Attempt> finalClaim(
      VerifiedTenantContext context,
      Map<String, Object> mandate,
      PaymentProvider provider,
      PaymentAttemptStore.Attempt attempt) {
    return store
        .current(context, Use.DISPATCH, (UUID) mandate.get("payment_method_id"))
        .flatMap(
            last -> {
              matching(last, mandate);
              if (!last.admissionAllowed())
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_UNAVAILABLE"));
              if (store.providers.require(
                      last.method().provider(),
                      last.method().merchant(),
                      last.method().profileVersion())
                  != provider)
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_PROVIDER_CHANGED"));
              return attempts
                  .claimRecurring(attempt, (UUID) mandate.get("id"))
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.ABORTED, "AUTOPAY_ADMISSION_CONFLICT")));
            });
  }

  private Mono<PaymentAttemptStore.Attempt> submit(Admission a) {
    var attempt = a.attempt();
    return Mono.defer(
            () ->
                a.provider()
                    .chargeRecurring(
                        attempt.merchant(),
                        new PaymentProvider.Invoice(
                            attempt.invoiceId(),
                            new PaymentProvider.Money(attempt.amountMinor(), attempt.currency())),
                        a.method(),
                        attempt.operationId(),
                        attempt.providerIdempotencyKey()))
        .timeout(Duration.ofSeconds(20))
        .switchIfEmpty(Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .flatMap(
            result -> {
              if (result.providerPaymentId() == null
                  || result.providerPaymentId().isBlank()
                  || !attempt.providerIdempotencyKey().equals(result.providerIdempotencyKey()))
                return Mono.error(new ProviderException(ProviderException.Reason.INVALID_STATE));
              var state =
                  switch (result.state()) {
                    case SUCCEEDED -> PaymentAttemptStore.State.SUCCEEDED;
                    case DECLINED -> PaymentAttemptStore.State.FAILED;
                    case PENDING -> PaymentAttemptStore.State.PENDING;
                    case UNKNOWN -> PaymentAttemptStore.State.UNKNOWN;
                  };
              return attempts.mark(attempt, state, result.providerPaymentId());
            })
        .onErrorResume(
            ProviderException.class,
            e ->
                e.reason() == ProviderException.Reason.UNKNOWN_OUTCOME
                    ? attempts.unknown(attempt)
                    : Mono.error(e))
        .onErrorResume(java.util.concurrent.TimeoutException.class, e -> attempts.unknown(attempt))
        .flatMap(result -> observer.get(result.tenantId(), result.id()));
  }
}
