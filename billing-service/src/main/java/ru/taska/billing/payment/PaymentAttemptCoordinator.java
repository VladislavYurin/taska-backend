package ru.taska.billing.payment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Durable claim commits before a network effect; an uncertain claim is never submitted again. */
public final class PaymentAttemptCoordinator {
  public interface Mapping {
    Mono<Void> register(PaymentAttemptStore.Attempt attempt);
  }

  private final PaymentAttemptStore attempts;
  private final PaymentProviderRegistry providers;
  private final BillingOperationStore operations;
  private final Mapping mapping;

  public PaymentAttemptCoordinator(
      PaymentAttemptStore attempts,
      PaymentProviderRegistry providers,
      BillingOperationStore operations,
      Mapping mapping) {
    this.attempts = Objects.requireNonNull(attempts);
    this.providers = Objects.requireNonNull(providers);
    this.operations = Objects.requireNonNull(operations);
    this.mapping = Objects.requireNonNull(mapping);
  }

  public Mono<PaymentAttemptStore.Attempt> create(
      PaymentAttemptStore.Request request,
      PaymentProvider.Merchant merchant,
      PaymentProvider.Invoice invoice) {
    return Mono.defer(
        () -> {
          if (!request.merchantAccount().equals(merchant.accountId())
              || request.environment() != merchant.environment()
              || !request.invoiceId().equals(invoice.invoiceId())) return Mono.error(conflict());
          return attempts
              .profileVersion(request, invoice)
              .flatMap(
                  profile -> {
                    providers.require(request.provider(), merchant, profile);
                    return attempts.canonicalSnapshot(request);
                  })
              .flatMap(
                  snapshot ->
                      operations.accept(
                          new BillingOperationStore.Request(
                              request.tenantId(),
                              "PAYMENT",
                              request.providerIdempotencyKey(),
                              digest(request, invoice, snapshot),
                              "PSP",
                              null,
                              null)))
              .flatMap(op -> attempts.accept(request, op.id(), invoice))
              .flatMap(
                  attempt -> {
                    if (attempt.terminal()) return Mono.just(attempt);
                    if (attempt.providerPaymentId() != null) return reconcile(attempt);
                    if (attempt.state() != PaymentAttemptStore.State.ACCEPTED)
                      return Mono.just(attempt);
                    var provider =
                        providers.require(
                            attempt.provider(), attempt.merchant(), attempt.profileVersion());
                    return attempts
                        .claim(attempt)
                        .flatMap(
                            claim ->
                                Mono.defer(
                                        () ->
                                            provider.createCheckout(
                                                claim.merchant(),
                                                new PaymentProvider.Invoice(
                                                    claim.invoiceId(),
                                                    new PaymentProvider.Money(
                                                        claim.amountMinor(), claim.currency())),
                                                claim.operationId(),
                                                claim.providerIdempotencyKey()))
                                    .timeout(Duration.ofSeconds(20))
                                    .switchIfEmpty(
                                        Mono.error(
                                            new ProviderException(
                                                ProviderException.Reason.UNKNOWN_OUTCOME)))
                                    .flatMap(
                                        checkout -> {
                                          PaymentProvider.require(
                                              checkout.providerPaymentId(), "providerPaymentId");
                                          if (!claim
                                              .providerIdempotencyKey()
                                              .equals(checkout.providerIdempotencyKey()))
                                            return Mono.error(conflict());
                                          return attempts.mark(
                                              claim,
                                              toState(checkout.state()),
                                              checkout.providerPaymentId());
                                        })
                                    .onErrorResume(error -> attempts.unknown(claim)))
                        .switchIfEmpty(attempts.find(attempt.tenantId(), attempt.id()));
                  })
              .flatMap(this::finish);
        });
  }

  /** Uses only durable original identity, independent of today's selected PSP/merchant. */
  public Mono<PaymentAttemptStore.Attempt> get(UUID tenant, UUID attemptId) {
    return attempts
        .find(tenant, attemptId)
        .flatMap(a -> a.terminal() || a.providerPaymentId() == null ? Mono.just(a) : reconcile(a))
        .flatMap(this::finish);
  }

  private Mono<PaymentAttemptStore.Attempt> reconcile(PaymentAttemptStore.Attempt attempt) {
    return Mono.defer(
            () ->
                providers
                    .require(attempt.provider(), attempt.merchant(), attempt.profileVersion())
                    .fetchPayment(attempt.merchant(), attempt.providerPaymentId()))
        .timeout(Duration.ofSeconds(20))
        .flatMap(
            payment -> {
              if (!attempt.providerPaymentId().equals(payment.providerPaymentId())
                  || !attempt.invoiceId().equals(payment.invoiceId())
                  || !new PaymentProvider.Money(attempt.amountMinor(), attempt.currency())
                      .equals(payment.amount()))
                return Mono.error(new ProviderException(ProviderException.Reason.INVALID_STATE));
              return attempts.mark(attempt, toState(payment.state()), payment.providerPaymentId());
            })
        .onErrorMap(
            java.util.concurrent.TimeoutException.class,
            e -> new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME))
        .onErrorResume(
            ProviderException.class,
            error ->
                error.reason() == ProviderException.Reason.UNKNOWN_OUTCOME
                    ? attempts.unknown(attempt)
                    : Mono.error(error));
  }

  private Mono<PaymentAttemptStore.Attempt> finish(PaymentAttemptStore.Attempt attempt) {
    Mono<Void> bound =
        attempt.providerPaymentId() == null
            ? Mono.empty()
            : Mono.defer(() -> mapping.register(attempt));
    var state =
        switch (attempt.state()) {
          case SUCCEEDED -> BillingOperationStore.State.SUCCEEDED;
          case FAILED, CANCELLED -> BillingOperationStore.State.FAILED;
          case UNKNOWN, SUBMITTED -> BillingOperationStore.State.UNKNOWN;
          default -> BillingOperationStore.State.PROCESSING;
        };
    return bound
        .then(operations.get(attempt.tenantId(), attempt.operationId()))
        .flatMap(op -> operations.mark(op, state, "{\"attemptId\":\"" + attempt.id() + "\"}"))
        .thenReturn(attempt);
  }

  private static ProviderException conflict() {
    return new ProviderException(ProviderException.Reason.IDEMPOTENCY_CONFLICT);
  }

  private static byte[] digest(
      PaymentAttemptStore.Request r, PaymentProvider.Invoice invoice, String snapshot) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String part :
          new String[] {
            r.tenantId().toString(),
            r.invoiceId().toString(),
            r.provider(),
            r.merchantAccount(),
            r.environment().name(),
            Long.toString(invoice.total().amountMinor()),
            invoice.total().currency(),
            snapshot
          }) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PaymentAttemptStore.State toState(PaymentProvider.State state) {
    return switch (state) {
      case PENDING -> PaymentAttemptStore.State.PENDING;
      case SUCCEEDED -> PaymentAttemptStore.State.SUCCEEDED;
      case DECLINED -> PaymentAttemptStore.State.FAILED;
      case UNKNOWN -> PaymentAttemptStore.State.UNKNOWN;
    };
  }
}
