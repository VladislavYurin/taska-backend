package ru.taska.billing.payment;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import reactor.core.publisher.Mono;

/** Coordinates durable attempt identity with the provider boundary. */
public final class PaymentAttemptCoordinator {
  private final PaymentAttemptStore attempts;
  private final PaymentProvider provider;
  private final BillingOperationStore operations;

  public PaymentAttemptCoordinator(PaymentAttemptStore attempts, PaymentProvider provider) {
    this(attempts, provider, null);
  }

  public PaymentAttemptCoordinator(PaymentAttemptStore attempts, PaymentProvider provider,
                                   BillingOperationStore operations) {
    this.attempts = Objects.requireNonNull(attempts);
    this.provider = Objects.requireNonNull(provider);
    this.operations = operations;
  }

  public Mono<PaymentAttemptStore.Attempt> create(
      PaymentAttemptStore.Request request,
      PaymentProvider.Merchant merchant,
      PaymentProvider.Invoice invoice,
      UUID operationId) {
    validateMerchant(request, merchant);
    Mono<PaymentAttemptStore.Attempt> flow = attempts.accept(request)
        .flatMap(attempt -> {
          if (attempt.state() == PaymentAttemptStore.State.SUCCEEDED
              || attempt.state() == PaymentAttemptStore.State.FAILED) {
            return Mono.just(attempt);
          }
          if (attempt.providerPaymentId() != null) {
            return reconcile(attempt, merchant);
          }
          // A provider may have accepted the request before the process timed out and
          // therefore left no object id locally. Replaying the exact PSP idempotency key
          // is the only safe recovery; a new key is never generated here.
          return provider.createCheckout(
                  merchant, invoice, operationId, request.providerIdempotencyKey())
              .flatMap(checkout -> attempts.mark(
                  attempt,
                  toState(checkout.state()),
                  checkout.providerPaymentId()))
              .onErrorResume(ProviderException.class, error ->
                  error.reason() == ProviderException.Reason.UNKNOWN_OUTCOME
                      ? attempts.unknown(attempt)
                      : Mono.error(error));
        });
    if (operations == null) return flow;
    var operation = new BillingOperationStore.Request(
        request.tenantId(), "PAYMENT", request.providerIdempotencyKey(),
        digest(request, invoice), "PSP", null, null);
    return operations.accept(operation)
        .flatMap(op -> flow
            .flatMap(result -> operations.mark(op,
                result.state() == PaymentAttemptStore.State.UNKNOWN
                    ? BillingOperationStore.State.UNKNOWN
                    : BillingOperationStore.State.SUCCEEDED,
                "{\"attemptId\":\"" + result.id() + "\"}")
                .thenReturn(result)));
  }

  private static byte[] digest(PaymentAttemptStore.Request request, PaymentProvider.Invoice invoice) {
    try {
      return MessageDigest.getInstance("SHA-256").digest((request.tenantId() + "|"
          + request.invoiceId() + "|" + request.provider() + "|"
          + request.merchantAccount() + "|" + request.environment() + "|"
          + invoice.total().amountMinor() + "|" + invoice.total().currency())
          .getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Mono<PaymentAttemptStore.Attempt> reconcile(
      PaymentAttemptStore.Attempt attempt, PaymentProvider.Merchant merchant) {
    var originalMerchant = new PaymentProvider.Merchant(
        attempt.merchantAccount(), attempt.environment());
    return provider.fetchPayment(originalMerchant, attempt.providerPaymentId())
        .flatMap(payment -> attempts.mark(
            attempt, toState(payment.state()), payment.providerPaymentId()))
        .onErrorResume(ProviderException.class, error ->
            error.reason() == ProviderException.Reason.UNKNOWN_OUTCOME
                ? attempts.unknown(attempt)
                : Mono.error(error));
  }

  private static void validateMerchant(
      PaymentAttemptStore.Request request, PaymentProvider.Merchant merchant) {
    Objects.requireNonNull(merchant);
    if (!request.merchantAccount().equals(merchant.accountId())
        || request.environment() != merchant.environment()) {
      throw new ProviderException(ProviderException.Reason.IDEMPOTENCY_CONFLICT);
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
