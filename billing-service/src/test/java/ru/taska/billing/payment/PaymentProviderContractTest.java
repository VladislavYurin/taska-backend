package ru.taska.billing.payment;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.payment.PaymentProvider.*;

/** Reusable adapter contract: future sandbox adapters provide a fresh fixture per test. */
abstract class PaymentProviderContractTest {
    protected record Fixture(PaymentProvider provider, Merchant merchant, Invoice invoice) {}
    protected abstract Fixture fixture();

    @Test void repeatedCheckoutKeepsProviderIdentityAndServerInvoice() {
        Fixture f = fixture();
        UUID operation = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Checkout first = f.provider().createCheckout(f.merchant(), f.invoice(), operation, key).block();
        Checkout second = f.provider().createCheckout(f.merchant(), f.invoice(), operation, key).block();
        assertEquals(first.providerPaymentId(), second.providerPaymentId());
        assertEquals(key, second.providerIdempotencyKey());
        Payment payment = f.provider().fetchPayment(f.merchant(), first.providerPaymentId()).block();
        assertEquals(f.invoice().invoiceId(), payment.invoiceId());
        assertEquals(f.invoice().total(), payment.amount());
    }

    @Test void sameKeyWithDifferentAmountCannotChangePayment() {
        Fixture f = fixture();
        UUID operation = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Checkout first = f.provider().createCheckout(f.merchant(), f.invoice(), operation, key).block();
        Invoice changed = new Invoice(f.invoice().invoiceId(), new Money(f.invoice().total().amountMinor() + 1,
                f.invoice().total().currency()));
        StepVerifier.create(f.provider().createCheckout(f.merchant(), changed, operation, key))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(ProviderException.class, error);
                    assertEquals(ProviderException.Reason.IDEMPOTENCY_CONFLICT, ((ProviderException) error).reason());
                }).verify();
        assertEquals(f.invoice().total(), f.provider().fetchPayment(f.merchant(), first.providerPaymentId()).block().amount());
    }

    @Test void unsignedWebhookIsRejected() {
        Fixture f = fixture();
        StepVerifier.create(f.provider().verifyWebhook(f.merchant(), "forged".getBytes(), "invalid"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(ProviderException.class, error);
                    assertEquals(ProviderException.Reason.INVALID_WEBHOOK, ((ProviderException) error).reason());
                }).verify();
    }
}
