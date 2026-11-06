package ru.taska.billing.payment;

import static ru.taska.billing.payment.PaymentProvider.Capabilities;
import static ru.taska.billing.payment.PaymentProvider.Checkout;
import static ru.taska.billing.payment.PaymentProvider.Environment;
import static ru.taska.billing.payment.PaymentProvider.EventKind;
import static ru.taska.billing.payment.PaymentProvider.Invoice;
import static ru.taska.billing.payment.PaymentProvider.Merchant;
import static ru.taska.billing.payment.PaymentProvider.Money;
import static ru.taska.billing.payment.PaymentProvider.Payment;
import static ru.taska.billing.payment.PaymentProvider.Refund;
import static ru.taska.billing.payment.PaymentProvider.RefundState;
import static ru.taska.billing.payment.PaymentProvider.State;
import static ru.taska.billing.payment.PaymentProvider.VerifiedEvent;
import static ru.taska.billing.payment.PaymentProvider.require;
import static ru.taska.billing.payment.ProviderException.Reason.IDEMPOTENCY_CONFLICT;
import static ru.taska.billing.payment.ProviderException.Reason.INVALID_STATE;
import static ru.taska.billing.payment.ProviderException.Reason.INVALID_WEBHOOK;
import static ru.taska.billing.payment.ProviderException.Reason.NOT_FOUND;
import static ru.taska.billing.payment.ProviderException.Reason.UNKNOWN_OUTCOME;
import static ru.taska.billing.payment.ProviderException.Reason.UNSUPPORTED_CAPABILITY;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Explicit test adapter. It has no Spring bean annotation and cannot initiate a real payment. */
public final class FakePaymentProvider implements PaymentProvider {
    public enum Scenario {
        SUCCESS,
        DECLINE,
        TIMEOUT_ACCEPTED,
        UNKNOWN
    }

    public record SignedWebhook(byte[] rawBody, String signature) {
        public SignedWebhook {
            rawBody = rawBody.clone();
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }

    private record Key(Merchant merchant, String idempotencyKey) {}

    private record PaymentAttempt(
            Invoice invoice,
            UUID operationId,
            String id,
            AtomicReference<State> state,
            AtomicReference<Instant> occurredAt) {}

    private record RefundAttempt(
            String paymentId,
            Money amount,
            UUID operationId,
            String id,
            AtomicReference<RefundState> state,
            AtomicReference<Instant> occurredAt) {}

    private final Map<Key, PaymentAttempt> paymentAttempts = new ConcurrentHashMap<>();
    private final Map<Key, RefundAttempt> refundAttempts = new ConcurrentHashMap<>();
    private final Map<String, Key> paymentIds = new ConcurrentHashMap<>();
    private final Map<String, Key> refundByPayment = new ConcurrentHashMap<>();
    private final Map<String, VerifiedEvent> events = new ConcurrentHashMap<>();
    private final AtomicReference<Scenario> nextPayment = new AtomicReference<>(Scenario.SUCCESS);
    private final AtomicReference<Scenario> nextRefund = new AtomicReference<>(Scenario.SUCCESS);
    private final byte[] webhookSecret;
    private final Clock clock;

    public FakePaymentProvider(byte[] webhookSecret) {
        this(webhookSecret, Clock.systemUTC());
    }

    public FakePaymentProvider(byte[] webhookSecret, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        if (webhookSecret == null || webhookSecret.length < 32)
            throw new IllegalArgumentException("fake webhook key must have at least 32 bytes");
        this.webhookSecret = webhookSecret.clone();
    }

    public void nextPayment(Scenario scenario) {
        nextPayment.set(Objects.requireNonNull(scenario));
    }

    public void nextRefund(Scenario scenario) {
        nextRefund.set(Objects.requireNonNull(scenario));
    }

    @Override
    public Mono<Capabilities> capabilities(Merchant merchant) {
        return Mono.fromSupplier(
                () -> {
                    Objects.requireNonNull(merchant);
                    requireTest(merchant);
                    return new Capabilities(true, true, true, false, false, true);
                });
    }

    @Override
    public Mono<Checkout> createCheckout(
            Merchant merchant, Invoice invoice, UUID operationId, String key) {
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        requireTest(merchant);
                        Objects.requireNonNull(invoice);
                        Objects.requireNonNull(operationId);
                        require(key, "providerIdempotencyKey");
                        Key scoped = new Key(merchant, key);
                        PaymentAttempt existing = paymentAttempts.get(scoped);
                        if (existing != null)
                            return Mono.just(
                                    checkout(matching(existing, invoice, operationId), key));
                        Scenario scenario = nextPayment.getAndSet(Scenario.SUCCESS);
                        PaymentAttempt attempt =
                                new PaymentAttempt(
                                        invoice,
                                        operationId,
                                        "fake-pay-" + UUID.randomUUID(),
                                        new AtomicReference<>(paymentState(scenario)),
                                        new AtomicReference<>(
                                                terminal(scenario) ? clock.instant() : null));
                        PaymentAttempt previous = paymentAttempts.putIfAbsent(scoped, attempt);
                        if (previous != null)
                            return Mono.just(
                                    checkout(matching(previous, invoice, operationId), key));
                        paymentIds.put(attempt.id(), scoped);
                        if (scenario == Scenario.TIMEOUT_ACCEPTED || scenario == Scenario.UNKNOWN)
                            return Mono.error(new ProviderException(UNKNOWN_OUTCOME));
                        return Mono.just(checkout(attempt, key));
                    }
                });
    }

    @Override
    public Mono<Payment> fetchPayment(Merchant merchant, String providerPaymentId) {
        return Mono.fromSupplier(
                () -> {
                    PaymentAttempt a = payment(merchant, providerPaymentId);
                    return new Payment(
                            a.id(), a.invoice().invoiceId(), a.invoice().total(), a.state().get());
                });
    }

    @Override
    public Mono<Refund> refund(
            Merchant merchant,
            String providerPaymentId,
            Money amount,
            UUID operationId,
            String key) {
        return Mono.defer(
                () -> {
                    synchronized (this) {
                        PaymentAttempt payment = payment(merchant, providerPaymentId);
                        Objects.requireNonNull(amount);
                        Objects.requireNonNull(operationId);
                        require(key, "providerIdempotencyKey");
                        Key scoped = new Key(merchant, key);
                        RefundAttempt existing = refundAttempts.get(scoped);
                        if (existing != null)
                            return Mono.just(
                                    refundResult(
                                            matching(
                                                    existing,
                                                    providerPaymentId,
                                                    amount,
                                                    operationId),
                                            key));
                        if (payment.state().get() != State.SUCCEEDED)
                            return Mono.error(new ProviderException(INVALID_STATE));
                        if (amount.amountMinor() < payment.invoice().total().amountMinor()
                                && amount.currency().equals(payment.invoice().total().currency()))
                            return Mono.error(new ProviderException(UNSUPPORTED_CAPABILITY));
                        if (!payment.invoice().total().equals(amount))
                            return Mono.error(new ProviderException(INVALID_STATE));
                        Key previousRefundKey = refundByPayment.get(providerPaymentId);
                        if (previousRefundKey != null
                                && refundAttempts.get(previousRefundKey).state().get()
                                        != RefundState.DECLINED)
                            return Mono.error(new ProviderException(INVALID_STATE));
                        Scenario scenario = nextRefund.getAndSet(Scenario.SUCCESS);
                        RefundAttempt attempt =
                                new RefundAttempt(
                                        providerPaymentId,
                                        amount,
                                        operationId,
                                        "fake-refund-" + UUID.randomUUID(),
                                        new AtomicReference<>(refundState(scenario)),
                                        new AtomicReference<>(
                                                terminal(scenario) ? clock.instant() : null));
                        RefundAttempt previous = refundAttempts.putIfAbsent(scoped, attempt);
                        if (previous != null)
                            return Mono.just(
                                    refundResult(
                                            matching(
                                                    previous,
                                                    providerPaymentId,
                                                    amount,
                                                    operationId),
                                            key));
                        refundByPayment.put(providerPaymentId, scoped);
                        if (scenario == Scenario.TIMEOUT_ACCEPTED || scenario == Scenario.UNKNOWN)
                            return Mono.error(new ProviderException(UNKNOWN_OUTCOME));
                        return Mono.just(refundResult(attempt, key));
                    }
                });
    }

    @Override
    public Mono<VerifiedEvent> verifyWebhook(Merchant merchant, byte[] rawBody, String signature) {
        return Mono.fromSupplier(
                () -> {
                    requireTest(merchant);
                    if (rawBody == null
                            || signature == null
                            || !MessageDigest.isEqual(
                                    hmac(rawBody).getBytes(StandardCharsets.US_ASCII),
                                    signature.getBytes(StandardCharsets.US_ASCII)))
                        throw new ProviderException(INVALID_WEBHOOK);
                    VerifiedEvent event = events.get(new String(rawBody, StandardCharsets.UTF_8));
                    if (event == null || !event.merchant().equals(merchant))
                        throw new ProviderException(INVALID_WEBHOOK);
                    return event;
                });
    }

    /** Test driver: models a later verified provider observation, never billing's PAID decision. */
    public synchronized SignedWebhook confirmPayment(
            Merchant merchant, String paymentId, State finalState, String eventId) {
        requireTest(merchant);
        PaymentAttempt a = payment(merchant, paymentId);
        if (finalState != State.SUCCEEDED && finalState != State.DECLINED)
            throw new ProviderException(INVALID_STATE);
        State previousState = a.state().get();
        if ((previousState == State.SUCCEEDED || previousState == State.DECLINED)
                && previousState != finalState) throw new ProviderException(INVALID_STATE);
        Instant occurred = a.occurredAt().get() == null ? clock.instant() : a.occurredAt().get();
        SignedWebhook signed =
                signedEvent(
                        new VerifiedEvent(
                                require(eventId, "eventId"),
                                paymentId,
                                EventKind.PAYMENT,
                                merchant,
                                a.invoice().invoiceId(),
                                a.invoice().total(),
                                occurred,
                                finalState,
                                null,
                                null));
        a.occurredAt().set(occurred);
        a.state().set(finalState);
        return signed;
    }

    public synchronized SignedWebhook confirmRefund(
            Merchant merchant, String key, RefundState finalState, String eventId) {
        requireTest(merchant);
        RefundAttempt a = refundAttempts.get(new Key(merchant, key));
        if (a == null) throw new ProviderException(NOT_FOUND);
        if (finalState != RefundState.SUCCEEDED && finalState != RefundState.DECLINED)
            throw new ProviderException(INVALID_STATE);
        RefundState previousState = a.state().get();
        if ((previousState == RefundState.SUCCEEDED || previousState == RefundState.DECLINED)
                && previousState != finalState) throw new ProviderException(INVALID_STATE);
        PaymentAttempt payment = payment(merchant, a.paymentId());
        Instant occurred = a.occurredAt().get() == null ? clock.instant() : a.occurredAt().get();
        SignedWebhook signed =
                signedEvent(
                        new VerifiedEvent(
                                require(eventId, "eventId"),
                                a.paymentId(),
                                EventKind.REFUND,
                                merchant,
                                payment.invoice().invoiceId(),
                                a.amount(),
                                occurred,
                                null,
                                finalState,
                                a.id()));
        a.occurredAt().set(occurred);
        a.state().set(finalState);
        return signed;
    }

    private SignedWebhook signedEvent(VerifiedEvent event) {
        String wire =
                "fake-v1:"
                        + encode(event.merchant().accountId())
                        + ":"
                        + event.merchant().environment().name()
                        + ":"
                        + encode(event.providerEventId());
        VerifiedEvent previous = events.putIfAbsent(wire, event);
        if (previous != null
                && (!previous.merchant().equals(event.merchant())
                        || !previous.providerPaymentId().equals(event.providerPaymentId())
                        || !Objects.equals(previous.providerRefundId(), event.providerRefundId())
                        || previous.kind() != event.kind()
                        || !previous.amount().equals(event.amount())
                        || previous.paymentState() != event.paymentState()
                        || previous.refundState() != event.refundState()))
            throw new ProviderException(IDEMPOTENCY_CONFLICT);
        byte[] raw = wire.getBytes(StandardCharsets.UTF_8);
        return new SignedWebhook(raw, hmac(raw));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean terminal(Scenario s) {
        return s == Scenario.SUCCESS || s == Scenario.DECLINE;
    }

    private String hmac(byte[] raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(raw));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    private static PaymentAttempt matching(PaymentAttempt a, Invoice invoice, UUID op) {
        if (!a.invoice().equals(invoice) || !a.operationId().equals(op))
            throw new ProviderException(IDEMPOTENCY_CONFLICT);
        return a;
    }

    private static RefundAttempt matching(
            RefundAttempt a, String paymentId, Money amount, UUID op) {
        if (!a.paymentId().equals(paymentId)
                || !a.amount().equals(amount)
                || !a.operationId().equals(op)) throw new ProviderException(IDEMPOTENCY_CONFLICT);
        return a;
    }

    private static Checkout checkout(PaymentAttempt a, String key) {
        return new Checkout(
                a.id(), "https://fake.invalid/checkout/" + a.id(), a.state().get(), key);
    }

    private static Refund refundResult(RefundAttempt a, String key) {
        return new Refund(a.id(), a.paymentId(), a.amount(), a.state().get(), key);
    }

    private PaymentAttempt payment(Merchant merchant, String id) {
        requireTest(merchant);
        require(id, "providerPaymentId");
        Key key = paymentIds.get(id);
        if (key == null || !key.merchant().equals(merchant)) throw new ProviderException(NOT_FOUND);
        return paymentAttempts.get(key);
    }

    private static void requireTest(Merchant merchant) {
        Objects.requireNonNull(merchant);
        if (merchant.environment() != Environment.TEST)
            throw new ProviderException(UNSUPPORTED_CAPABILITY);
    }

    private static State paymentState(Scenario s) {
        return switch (s) {
            case SUCCESS -> State.SUCCEEDED;
            case DECLINE -> State.DECLINED;
            case TIMEOUT_ACCEPTED -> State.PENDING;
            case UNKNOWN -> State.UNKNOWN;
        };
    }

    private static RefundState refundState(Scenario s) {
        return switch (s) {
            case SUCCESS -> RefundState.SUCCEEDED;
            case DECLINE -> RefundState.DECLINED;
            case TIMEOUT_ACCEPTED -> RefundState.PENDING;
            case UNKNOWN -> RefundState.UNKNOWN;
        };
    }
}
