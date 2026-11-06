package ru.taska.billing.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static ru.taska.billing.payment.ProviderException.Reason.IDEMPOTENCY_CONFLICT;
import static ru.taska.billing.payment.ProviderException.Reason.INVALID_STATE;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.taska.billing.payment.PaymentProvider.Environment;
import ru.taska.billing.payment.PaymentProvider.Invoice;
import ru.taska.billing.payment.PaymentProvider.Merchant;
import ru.taska.billing.payment.PaymentProvider.Money;
import ru.taska.billing.payment.PaymentProvider.RefundState;
import ru.taska.billing.payment.PaymentProvider.State;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(20)
class FakePaymentProviderConcurrencyTest {
    final Merchant merchant = new Merchant("merchant-A", Environment.TEST);
    final Invoice invoice = new Invoice(UUID.randomUUID(), new Money(2000, "EUR"));

    private record Result(
            FakePaymentProvider.SignedWebhook event, ProviderException.Reason reason) {}

    Result confirm(CyclicBarrier barrier, Callable<FakePaymentProvider.SignedWebhook> confirm)
            throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return new Result(confirm.call(), null);
        } catch (ProviderException e) {
            return new Result(null, e.reason());
        }
    }

    @Test
    void competingPaymentOutcomesPublishExactlyOneTerminalState() throws Exception {
        try (var threads = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 24; iteration++) {
                var fake = new FakePaymentProvider(new byte[32]);
                fake.nextPayment(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
                UUID operation = UUID.randomUUID();
                assertThrows(
                        ProviderException.class,
                        () -> fake.createCheckout(merchant, invoice, operation, "pay").block());
                var payment = fake.createCheckout(merchant, invoice, operation, "pay").block();
                var barrier = new CyclicBarrier(2);
                var futures =
                        threads.<Result>invokeAll(
                                List.of(
                                        () ->
                                                confirm(
                                                        barrier,
                                                        () ->
                                                                fake.confirmPayment(
                                                                        merchant,
                                                                        payment.providerPaymentId(),
                                                                        State.SUCCEEDED,
                                                                        "success")),
                                        () ->
                                                confirm(
                                                        barrier,
                                                        () ->
                                                                fake.confirmPayment(
                                                                        merchant,
                                                                        payment.providerPaymentId(),
                                                                        State.DECLINED,
                                                                        "decline"))));
                var a = futures.get(0).get();
                var b = futures.get(1).get();
                assertEquals(1, List.of(a, b).stream().filter(x -> x.event() != null).count());
                var winner = a.event() == null ? b : a;
                var loser = a.event() == null ? a : b;
                assertEquals(INVALID_STATE, loser.reason());
                assertEquals(
                        fake.verifyWebhook(
                                        merchant,
                                        winner.event().rawBody(),
                                        winner.event().signature())
                                .block()
                                .paymentState(),
                        fake.fetchPayment(merchant, payment.providerPaymentId()).block().state());
            }
        }
    }

    @Test
    void competingRefundOutcomesPublishExactlyOneTerminalState() throws Exception {
        try (var threads = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 24; iteration++) {
                var fake = new FakePaymentProvider(new byte[32]);
                var payment =
                        fake.createCheckout(merchant, invoice, UUID.randomUUID(), "pay").block();
                fake.nextRefund(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
                UUID operation = UUID.randomUUID();
                assertThrows(
                        ProviderException.class,
                        () ->
                                fake.refund(
                                                merchant,
                                                payment.providerPaymentId(),
                                                invoice.total(),
                                                operation,
                                                "refund")
                                        .block());
                var barrier = new CyclicBarrier(2);
                var futures =
                        threads.<Result>invokeAll(
                                List.of(
                                        () ->
                                                confirm(
                                                        barrier,
                                                        () ->
                                                                fake.confirmRefund(
                                                                        merchant,
                                                                        "refund",
                                                                        RefundState.SUCCEEDED,
                                                                        "success")),
                                        () ->
                                                confirm(
                                                        barrier,
                                                        () ->
                                                                fake.confirmRefund(
                                                                        merchant,
                                                                        "refund",
                                                                        RefundState.DECLINED,
                                                                        "decline"))));
                var a = futures.get(0).get();
                var b = futures.get(1).get();
                assertEquals(1, List.of(a, b).stream().filter(x -> x.event() != null).count());
                var winner = a.event() == null ? b : a;
                assertEquals(INVALID_STATE, (a.event() == null ? a : b).reason());
                assertEquals(
                        fake.verifyWebhook(
                                        merchant,
                                        winner.event().rawBody(),
                                        winner.event().signature())
                                .block()
                                .refundState(),
                        fake.refund(
                                        merchant,
                                        payment.providerPaymentId(),
                                        invoice.total(),
                                        operation,
                                        "refund")
                                .block()
                                .state());
            }
        }
    }

    @Test
    void aDeclinedRefundAllowsOneNewAttemptWithoutChangingTheOldReceipt() {
        var fake = new FakePaymentProvider(new byte[32]);
        var payment = fake.createCheckout(merchant, invoice, UUID.randomUUID(), "pay").block();
        UUID declinedOperation = UUID.randomUUID();
        fake.nextRefund(FakePaymentProvider.Scenario.DECLINE);
        var declined =
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                declinedOperation,
                                "declined")
                        .block();
        assertEquals(RefundState.DECLINED, declined.state());
        var next =
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                UUID.randomUUID(),
                                "retry-new-operation")
                        .block();
        assertEquals(RefundState.SUCCEEDED, next.state());
        assertNotEquals(declined.providerRefundId(), next.providerRefundId());
        assertEquals(
                declined,
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                declinedOperation,
                                "declined")
                        .block());
        assertThrows(
                ProviderException.class,
                () ->
                        fake.refund(
                                        merchant,
                                        payment.providerPaymentId(),
                                        invoice.total(),
                                        UUID.randomUUID(),
                                        "third")
                                .block());
    }

    @Test
    void refundObservationsIdentifyTheirAttemptAndRejectRebindingAnOldEvent() {
        var fake = new FakePaymentProvider(new byte[32]);
        var payment = fake.createCheckout(merchant, invoice, UUID.randomUUID(), "pay").block();
        UUID firstOperation = UUID.randomUUID(), secondOperation = UUID.randomUUID();
        fake.nextRefund(FakePaymentProvider.Scenario.DECLINE);
        var first =
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                firstOperation,
                                "first")
                        .block();
        var old = fake.confirmRefund(merchant, "first", RefundState.DECLINED, "shared-event");
        fake.nextRefund(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
        assertThrows(
                ProviderException.class,
                () ->
                        fake.refund(
                                        merchant,
                                        payment.providerPaymentId(),
                                        invoice.total(),
                                        secondOperation,
                                        "second")
                                .block());
        var second =
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                secondOperation,
                                "second")
                        .block();
        var error =
                assertThrows(
                        ProviderException.class,
                        () ->
                                fake.confirmRefund(
                                        merchant, "second", RefundState.DECLINED, "shared-event"));
        assertEquals(IDEMPOTENCY_CONFLICT, error.reason());
        assertEquals(
                RefundState.PENDING,
                fake.refund(
                                merchant,
                                payment.providerPaymentId(),
                                invoice.total(),
                                secondOperation,
                                "second")
                        .block()
                        .state());
        var current = fake.confirmRefund(merchant, "second", RefundState.DECLINED, "second-event");
        assertEquals(
                first.providerRefundId(),
                fake.verifyWebhook(merchant, old.rawBody(), old.signature())
                        .block()
                        .providerRefundId());
        assertEquals(
                second.providerRefundId(),
                fake.verifyWebhook(merchant, current.rawBody(), current.signature())
                        .block()
                        .providerRefundId());
        assertNotEquals(first.providerRefundId(), second.providerRefundId());
    }

    @Test
    void laterDeliveriesKeepTheActualPaymentAndRefundOccurrence() {
        Instant start = Instant.parse("2026-01-31T12:00:00Z");
        var time = new AtomicReference<>(start);
        Clock clock =
                new Clock() {
                    public Instant instant() {
                        return time.get();
                    }

                    public ZoneId getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(ZoneId zone) {
                        return this;
                    }
                };
        var fake = new FakePaymentProvider(new byte[32], clock);
        var payment = fake.createCheckout(merchant, invoice, UUID.randomUUID(), "pay").block();
        time.set(start.plusSeconds(86400));
        var first =
                fake.confirmPayment(
                        merchant, payment.providerPaymentId(), State.SUCCEEDED, "first");
        time.set(start.plusSeconds(2 * 86400));
        var second =
                fake.confirmPayment(
                        merchant, payment.providerPaymentId(), State.SUCCEEDED, "second");
        assertEquals(
                start,
                fake.verifyWebhook(merchant, first.rawBody(), first.signature())
                        .block()
                        .occurredAt());
        assertEquals(
                start,
                fake.verifyWebhook(merchant, second.rawBody(), second.signature())
                        .block()
                        .occurredAt());
        Instant refunded = time.get();
        fake.refund(
                        merchant,
                        payment.providerPaymentId(),
                        invoice.total(),
                        UUID.randomUUID(),
                        "refund")
                .block();
        time.set(refunded.plusSeconds(86400));
        var event =
                fake.confirmRefund(merchant, "refund", RefundState.SUCCEEDED, "refund-delivery");
        assertEquals(
                refunded,
                fake.verifyWebhook(merchant, event.rawBody(), event.signature())
                        .block()
                        .occurredAt());
    }

    @Test
    void sameEventIdAtDifferentMerchantsCannotRebindSignedWebhooks() {
        var fake = new FakePaymentProvider(new byte[32]);
        var other = new Merchant("merchant-B", Environment.TEST);
        var a = fake.createCheckout(merchant, invoice, UUID.randomUUID(), "pay").block();
        var b = fake.createCheckout(other, invoice, UUID.randomUUID(), "pay").block();
        var wa =
                fake.confirmPayment(
                        merchant, a.providerPaymentId(), State.SUCCEEDED, "shared-event-id");
        var wb =
                fake.confirmPayment(
                        other, b.providerPaymentId(), State.SUCCEEDED, "shared-event-id");
        assertEquals(
                a.providerPaymentId(),
                fake.verifyWebhook(merchant, wa.rawBody(), wa.signature())
                        .block()
                        .providerPaymentId());
        assertEquals(
                b.providerPaymentId(),
                fake.verifyWebhook(other, wb.rawBody(), wb.signature())
                        .block()
                        .providerPaymentId());
        assertThrows(
                ProviderException.class,
                () -> fake.verifyWebhook(other, wa.rawBody(), wa.signature()).block());
        assertThrows(
                ProviderException.class,
                () -> fake.verifyWebhook(merchant, wb.rawBody(), wb.signature()).block());
    }
}
