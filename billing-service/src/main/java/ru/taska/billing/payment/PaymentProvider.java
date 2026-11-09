package ru.taska.billing.payment;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PSP boundary. No provider SDK types or browser assertions cross this interface. */
public interface PaymentProvider {
    Mono<Capabilities> capabilities(Merchant merchant);

    Mono<Checkout> createCheckout(
            Merchant merchant, Invoice invoice, UUID operationId, String providerIdempotencyKey);

    Mono<Payment> fetchPayment(Merchant merchant, String providerPaymentId);

    Mono<Refund> refund(
            Merchant merchant,
            String providerPaymentId,
            Money amount,
            UUID operationId,
            String providerIdempotencyKey);

    Mono<VerifiedEvent> verifyWebhook(Merchant merchant, byte[] rawBody, String signature);

    enum Environment {
        TEST,
        LIVE
    }

    enum State {
        PENDING,
        SUCCEEDED,
        DECLINED,
        UNKNOWN
    }

    enum RefundState {
        PENDING,
        SUCCEEDED,
        DECLINED,
        UNKNOWN
    }

    enum EventKind {
        PAYMENT,
        REFUND
    }

    record Merchant(String accountId, Environment environment) {
        public Merchant {
            require(accountId, "accountId");
            Objects.requireNonNull(environment);
        }
    }

    record Money(long amountMinor, String currency) {
        public Money {
            if (amountMinor <= 0)
                throw new IllegalArgumentException("amountMinor must be positive");
            if (!"EUR".equals(currency) && !"USD".equals(currency))
                throw new IllegalArgumentException("unsupported currency");
        }
    }

    record Invoice(UUID invoiceId, Money total) {
        public Invoice {
            Objects.requireNonNull(invoiceId);
            Objects.requireNonNull(total);
        }
    }

    record Capabilities(
            boolean hostedCheckout,
            boolean statusLookup,
            boolean fullRefunds,
            boolean partialRefunds,
            boolean recurring,
            boolean signedWebhooks) {}

    record Checkout(
            String providerPaymentId,
            String hostedUrl,
            State state,
            String providerIdempotencyKey) {}

    /** Provider-reported financial occurrence; null means insufficient evidence for settlement. */
    record Payment(String providerPaymentId, UUID invoiceId, Money amount, State state, Instant occurredAt) {
        public Payment(String providerPaymentId, UUID invoiceId, Money amount, State state) {
            this(providerPaymentId, invoiceId, amount, state, null);
        }
    }

    record Refund(
            String providerRefundId,
            String providerPaymentId,
            Money amount,
            RefundState state,
            String providerIdempotencyKey) {}

    /**
     * Normalized observation; refund ID identifies the exact attempt. Billing owns dedupe/ledger.
     */
    record VerifiedEvent(
            String providerEventId,
            String providerPaymentId,
            EventKind kind,
            Merchant merchant,
            UUID invoiceId,
            Money amount,
            Instant occurredAt,
            State paymentState,
            RefundState refundState,
            String providerRefundId) {
        public VerifiedEvent {
            Objects.requireNonNull(providerEventId);
            Objects.requireNonNull(providerPaymentId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(merchant);
            Objects.requireNonNull(invoiceId);
            Objects.requireNonNull(amount);
            Objects.requireNonNull(occurredAt);
            if (kind == EventKind.REFUND) require(providerRefundId, "providerRefundId");
            else if (providerRefundId != null)
                throw new IllegalArgumentException("payment event cannot identify a refund");
            if ((kind == EventKind.PAYMENT) != (paymentState != null)
                    || (kind == EventKind.REFUND) != (refundState != null))
                throw new IllegalArgumentException(
                        "exactly one outcome matching event kind is required");
        }
    }

    static String require(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
