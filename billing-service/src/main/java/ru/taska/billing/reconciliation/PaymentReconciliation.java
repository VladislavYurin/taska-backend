package ru.taska.billing.reconciliation;

import java.time.Duration;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.billing.ingress.*;
import ru.taska.billing.payment.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Read-only provider recovery. Only the common TAS-352 settlement may change financial rights. */
public final class PaymentReconciliation {
  private final PaymentReconciliationStore jobs;
  private final PaymentAttemptStore attempts;
  private final BillingOperationStore operations;
  private final PaymentProviderRegistry providers;
  private final ProviderWebhookRegistry profiles;
  private final ProviderInboxStore inbox;
  private final ProviderInbox.SettlementDelivery settlement;

  public PaymentReconciliation(
      PaymentReconciliationStore jobs,
      PaymentAttemptStore attempts,
      BillingOperationStore operations,
      PaymentProviderRegistry providers,
      ProviderWebhookRegistry profiles,
      ProviderInboxStore inbox,
      ProviderInbox.SettlementDelivery settlement) {
    this.jobs = jobs;
    this.attempts = attempts;
    this.operations = operations;
    this.providers = providers;
    this.profiles = profiles;
    this.inbox = inbox;
    this.settlement = settlement;
  }

  public Mono<PaymentReconciliationStore.Status> process(UUID tenant, UUID attempt, UUID token) {
    return authorize(tenant)
        .then(jobs.lease(tenant, attempt, token))
        .flatMap(
            job ->
                attempts
                    .find(tenant, attempt)
                    .flatMap(a -> reconcile(job, a))
                    .onErrorResume(
                        error -> {
                          // Authorization and lease admission are outside this handler. Never
                          // hide a downstream authorization/integrity failure as provider outage.
                          if (error instanceof IllegalStateException
                              || error instanceof IllegalArgumentException)
                            return Mono.error(error);
                          if (error instanceof DomainException d
                              && (d.getStatus() == DomainStatus.PERMISSION_DENIED
                                  || d.getStatus() == DomainStatus.UNAUTHENTICATED
                                  || d.getStatus() == DomainStatus.INVALID_ARGUMENT))
                            return Mono.error(error);
                          var rpc = io.grpc.Status.fromThrowable(error).getCode();
                          if (rpc == io.grpc.Status.Code.PERMISSION_DENIED
                              || rpc == io.grpc.Status.Code.UNAUTHENTICATED
                              || rpc == io.grpc.Status.Code.INVALID_ARGUMENT)
                            return Mono.error(error);
                          if (error instanceof ProviderException p
                              && p.reason() != ProviderException.Reason.UNKNOWN_OUTCOME)
                            return jobs.manual(job, "PROVIDER_" + p.reason().name());
                          if (error instanceof DomainException d
                              && d.getStatus() == DomainStatus.FAILED_PRECONDITION)
                            return jobs.manual(
                                job,
                                java.util.Set.of(
                                            "PAYMENT_BINDING_MISMATCH",
                                            "INVALID_OCCURRENCE",
                                            "PAYMENT_FACT_CONFLICT",
                                            "PAYMENT_OCCURRENCE_CONFLICT")
                                        .contains(d.getMessage())
                                    ? d.getMessage()
                                    : "PAYMENT_EVIDENCE_CONFLICT");
                          return jobs.retry(job, "DEPENDENCY_UNAVAILABLE");
                        }));
  }

  private Mono<Void> authorize(UUID tenant) {
    return VerifiedTenantContext.current()
        .map(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (!tenant.toString().equals(c.tenantId())
                  || c.actorId() != null
                  || !"reconcilePaymentAttempt".equals(c.action())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService())
                  || !c.scopes().contains("write")
                  || !c.scopes().contains("payment:reconcile"))
                throw new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return c;
            })
        .then();
  }

  private Mono<PaymentReconciliationStore.Status> reconcile(
      PaymentReconciliationStore.Job job, PaymentAttemptStore.Attempt a) {
    if (a.state() == PaymentAttemptStore.State.FAILED
        || a.state() == PaymentAttemptStore.State.CANCELLED)
      return completeOperation(a, false).then(jobs.done(job));
    if (a.providerPaymentId() == null || a.providerPaymentId().isBlank())
      return jobs.manual(job, "ORIGINAL_OBJECT_ID_REQUIRED");
    return inbox
        .existingEvidence(a)
        .map(java.util.Optional::of)
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(
            existing -> {
              if (existing.isPresent()) return settle(job, a, existing.get());
              return Mono.defer(
                  () -> {
                    var adapter = providers.require(a.provider(), a.merchant(), a.profileVersion());
                    var profile =
                        profiles.require(
                            new ProviderWebhookRegistry.Identity(
                                a.provider(), a.merchantAccount(), a.environment()));
                    if (!profile.profileVersions().contains(a.profileVersion()))
                      return jobs.manual(job, "ORIGINAL_PROFILE_UNAVAILABLE");
                    return adapter
                        .fetchPayment(a.merchant(), a.providerPaymentId())
                        .timeout(Duration.ofSeconds(20))
                        .switchIfEmpty(
                            Mono.error(
                                new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
                        .flatMap(
                            p -> {
                              if (!a.providerPaymentId().equals(p.providerPaymentId())
                                  || !a.invoiceId().equals(p.invoiceId())
                                  || !new PaymentProvider.Money(a.amountMinor(), a.currency())
                                      .equals(p.amount())
                                  || p.state() == null)
                                return jobs.manual(job, "PAYMENT_BINDING_MISMATCH");
                              if (p.state() == PaymentProvider.State.SUCCEEDED) {
                                if (p.occurredAt() == null)
                                  return jobs.manual(job, "PAYMENT_OCCURRENCE_REQUIRED");
                                return inbox
                                    .bind(
                                        a.tenantId(), a.invoiceId(), profile, a.providerPaymentId())
                                    .then(inbox.recordReconciliation(a, p))
                                    .flatMap(e -> settle(job, a, e));
                              }
                              if (a.state() == PaymentAttemptStore.State.SUCCEEDED)
                                return jobs.manual(job, "PAYMENT_OUTCOME_CONFLICT");
                              if (p.state() == PaymentProvider.State.DECLINED)
                                return attempts
                                    .mark(
                                        a, PaymentAttemptStore.State.FAILED, a.providerPaymentId())
                                    .flatMap(
                                        current ->
                                            current.state() == PaymentAttemptStore.State.FAILED
                                                ? completeOperation(current, false)
                                                    .then(jobs.done(job))
                                                : jobs.retry(job, "CONCURRENT_OBSERVATION"));
                              return attempts
                                  .mark(
                                      a,
                                      p.state() == PaymentProvider.State.PENDING
                                          ? PaymentAttemptStore.State.PENDING
                                          : PaymentAttemptStore.State.UNKNOWN,
                                      a.providerPaymentId())
                                  .then(
                                      jobs.retry(
                                          job,
                                          p.state() == PaymentProvider.State.PENDING
                                              ? "PROVIDER_PENDING"
                                              : "PROVIDER_UNKNOWN"));
                            });
                  });
            });
  }

  private Mono<PaymentReconciliationStore.Status> settle(
      PaymentReconciliationStore.Job job, PaymentAttemptStore.Attempt a, UUID evidence) {
    return settlement
        .apply(a.tenantId(), evidence)
        .then(attempts.mark(a, PaymentAttemptStore.State.SUCCEEDED, a.providerPaymentId()))
        .flatMap(
            current ->
                current.state() == PaymentAttemptStore.State.SUCCEEDED
                    ? completeOperation(current, true).then(jobs.done(job))
                    : jobs.manual(job, "PAYMENT_OUTCOME_CONFLICT"));
  }

  private Mono<Void> completeOperation(PaymentAttemptStore.Attempt a, boolean succeeded) {
    return operations
        .get(a.tenantId(), a.operationId())
        .flatMap(
            op ->
                operations.mark(
                    op,
                    succeeded
                        ? BillingOperationStore.State.SUCCEEDED
                        : BillingOperationStore.State.FAILED,
                    "{\"attemptId\":\"" + a.id() + "\"}"))
        .then();
  }
}
