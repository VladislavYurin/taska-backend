package ru.taska.billing.payment;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.payment.PaymentProvider.*;
import static ru.taska.billing.payment.ProviderException.Reason.*;

class FakePaymentProviderTest extends PaymentProviderContractTest {
    private final FakePaymentProvider fake = new FakePaymentProvider("a fixture key with at least thirty-two bytes".getBytes(StandardCharsets.UTF_8));
    private final Merchant merchant = new Merchant("merchant-A", Environment.TEST);
    private final Invoice invoice = new Invoice(UUID.randomUUID(), new Money(2000, "EUR"));
    private final UUID operation = UUID.randomUUID();

    @Override protected Fixture fixture() { return new Fixture(fake, merchant, invoice); }

    @Test void repeatUsesOneProviderPaymentAndRejectsChangedInvoice() {
        Checkout first = fake.createCheckout(merchant, invoice, operation, "payment-1").block();
        Checkout repeated = fake.createCheckout(merchant, invoice, operation, "payment-1").block();
        assertEquals(first, repeated);
        assertEquals(invoice.total(), fake.fetchPayment(merchant, first.providerPaymentId()).block().amount());
        Invoice changed = new Invoice(invoice.invoiceId(), new Money(2500, "EUR"));
        StepVerifier.create(fake.createCheckout(merchant, changed, operation, "payment-1"))
                .expectErrorSatisfies(error -> assertReason(error, IDEMPOTENCY_CONFLICT)).verify();
        assertEquals(invoice.total(), fake.fetchPayment(merchant, first.providerPaymentId()).block().amount());
    }

    @Test void timeoutCanBeReconciledAndLateWebhookMustBeVerified() {
        fake.nextPayment(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
        StepVerifier.create(fake.createCheckout(merchant, invoice, operation, "payment-2"))
                .expectErrorSatisfies(error -> assertReason(error, UNKNOWN_OUTCOME)).verify();
        Checkout replay = fake.createCheckout(merchant, invoice, operation, "payment-2").block();
        assertEquals(State.PENDING, replay.state());
        assertEquals(State.PENDING, fake.fetchPayment(merchant, replay.providerPaymentId()).block().state());
        var webhook = fake.confirmPayment(merchant, replay.providerPaymentId(), State.SUCCEEDED, "event-1");
        assertEquals(State.SUCCEEDED, fake.fetchPayment(merchant, replay.providerPaymentId()).block().state());
        VerifiedEvent verified = fake.verifyWebhook(merchant, webhook.rawBody(), webhook.signature()).block();
        assertEquals(invoice.total(), verified.amount());
        assertEquals("event-1", verified.providerEventId());
        assertEquals(State.SUCCEEDED, verified.paymentState());
        assertEquals(verified, fake.verifyWebhook(merchant, webhook.rawBody(), webhook.signature()).block());
        assertReason(assertThrows(ProviderException.class, () -> fake.confirmPayment(merchant, replay.providerPaymentId(), State.DECLINED, "event-1")), INVALID_STATE);
        assertEquals(State.SUCCEEDED, fake.fetchPayment(merchant, replay.providerPaymentId()).block().state());
        byte[] tampered = webhook.rawBody(); tampered[0] ^= 1;
        StepVerifier.create(fake.verifyWebhook(merchant, tampered, webhook.signature()))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_WEBHOOK)).verify();
        StepVerifier.create(fake.verifyWebhook(new Merchant("merchant-B", Environment.TEST), webhook.rawBody(), webhook.signature()))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_WEBHOOK)).verify();
    }

    @Test void declineAndUnknownDoNotBecomeSuccess() {
        fake.nextPayment(FakePaymentProvider.Scenario.DECLINE);
        Checkout declined = fake.createCheckout(merchant, invoice, operation, "decline").block();
        assertEquals(State.DECLINED, fake.fetchPayment(merchant, declined.providerPaymentId()).block().state());
        StepVerifier.create(fake.refund(merchant, declined.providerPaymentId(), invoice.total(), UUID.randomUUID(), "refund-declined"))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_STATE)).verify();
        fake.nextPayment(FakePaymentProvider.Scenario.UNKNOWN);
        UUID unknownOperation = UUID.randomUUID();
        StepVerifier.create(fake.createCheckout(merchant, invoice, unknownOperation, "unknown"))
                .expectErrorSatisfies(error -> assertReason(error, UNKNOWN_OUTCOME)).verify();
        Checkout unknown = fake.createCheckout(merchant, invoice, unknownOperation, "unknown").block();
        assertEquals(State.UNKNOWN, unknown.state());
        assertEquals(State.UNKNOWN, fake.fetchPayment(merchant, unknown.providerPaymentId()).block().state());
        assertEquals(unknown.providerPaymentId(), fake.createCheckout(merchant, invoice, unknownOperation, "unknown").block().providerPaymentId());
    }

    @Test void refundWaitsForConfirmedPaymentAndLeavesNoReservationOnRefusal() {
        fake.nextPayment(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
        StepVerifier.create(fake.createCheckout(merchant, invoice, operation, "pending"))
                .expectErrorSatisfies(error -> assertReason(error, UNKNOWN_OUTCOME)).verify();
        Checkout pending = fake.createCheckout(merchant, invoice, operation, "pending").block();
        UUID refundOperation = UUID.randomUUID();
        StepVerifier.create(fake.refund(merchant, pending.providerPaymentId(), invoice.total(), refundOperation, "refund-after-payment"))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_STATE)).verify();
        fake.confirmPayment(merchant, pending.providerPaymentId(), State.SUCCEEDED, "payment-confirmed");
        assertEquals(RefundState.SUCCEEDED, fake.refund(merchant, pending.providerPaymentId(), invoice.total(), refundOperation,
                "refund-after-payment").block().state());
    }

    @Test void refundIsFullIdempotentAndCanConfirmLater() {
        Checkout paid = fake.createCheckout(merchant, invoice, operation, "paid").block();
        UUID refundOp = UUID.randomUUID();
        fake.nextRefund(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
        StepVerifier.create(fake.refund(merchant, paid.providerPaymentId(), invoice.total(), refundOp, "refund-1"))
                .expectErrorSatisfies(error -> assertReason(error, UNKNOWN_OUTCOME)).verify();
        Refund replay = fake.refund(merchant, paid.providerPaymentId(), invoice.total(), refundOp, "refund-1").block();
        assertEquals(RefundState.PENDING, replay.state());
        var webhook = fake.confirmRefund(merchant, "refund-1", RefundState.SUCCEEDED, "refund-event");
        assertEquals(RefundState.SUCCEEDED, fake.verifyWebhook(merchant, webhook.rawBody(), webhook.signature()).block().refundState());
        assertEquals(RefundState.SUCCEEDED, fake.refund(merchant, paid.providerPaymentId(), invoice.total(), refundOp, "refund-1").block().state());
        StepVerifier.create(fake.refund(merchant, paid.providerPaymentId(), new Money(1000, "EUR"), refundOp, "refund-1"))
                .expectErrorSatisfies(error -> assertReason(error, IDEMPOTENCY_CONFLICT)).verify();
        StepVerifier.create(fake.refund(merchant, paid.providerPaymentId(), invoice.total(), UUID.randomUUID(), "second-refund"))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_STATE)).verify();
        StepVerifier.create(fake.refund(merchant, paid.providerPaymentId(), new Money(1000, "EUR"), UUID.randomUUID(), "partial"))
                .expectErrorSatisfies(error -> assertReason(error, INVALID_STATE)).verify();
    }

    @Test void testAdapterRejectsLiveAndCrossMerchantAccess() {
        Merchant live = new Merchant("merchant-A", Environment.LIVE);
        StepVerifier.create(fake.capabilities(live)).expectErrorSatisfies(error -> assertReason(error, UNSUPPORTED_CAPABILITY)).verify();
        StepVerifier.create(fake.createCheckout(live, invoice, operation, "live"))
                .expectErrorSatisfies(error -> assertReason(error, UNSUPPORTED_CAPABILITY)).verify();
        Checkout paid = fake.createCheckout(merchant, invoice, operation, "test").block();
        StepVerifier.create(fake.fetchPayment(new Merchant("merchant-B", Environment.TEST), paid.providerPaymentId()))
                .expectErrorSatisfies(error -> assertReason(error, NOT_FOUND)).verify();
    }

    @Test void moneyRejectsInvalidAmountAndCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new Money(0, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> new Money(-1, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> new Money(2000, "GBP"));
    }

    private static void assertReason(Throwable error, ProviderException.Reason reason) {
        assertInstanceOf(ProviderException.class, error);
        assertEquals(reason, ((ProviderException) error).reason());
    }
}
