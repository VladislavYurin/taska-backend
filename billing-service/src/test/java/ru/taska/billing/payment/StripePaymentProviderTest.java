package ru.taska.billing.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class StripePaymentProviderTest {
  private static final UUID INVOICE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

  @Test
  void sandboxConfigurationRejectsLiveKeyAndOpenRedirect() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StripePaymentProvider.Configuration(
            URI.create("https://api.stripe.com"), "sk_live_secret", "whsec_test",
            URI.create("https://taska.example/ok"), URI.create("https://taska.example/cancel"), 300));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StripePaymentProvider.Configuration(
            URI.create("https://api.stripe.com"), "sk_test_secret", "whsec_test",
            URI.create("http://attacker.example/ok"), URI.create("https://taska.example/cancel"), 300));
  }

  @Test
  void verifiesFreshStripeWebhookAndRejectsWrongOrExpiredSignature() throws Exception {
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var secret = "whsec_test";
    var provider = new StripePaymentProvider(
        org.springframework.web.reactive.function.client.WebClient.builder(),
        configuration(secret), new ObjectMapper(), clock);
    var body = ("{\"id\":\"evt_1\",\"created\":1731240000,\"type\":\"checkout.session.completed\","
        + "\"data\":{\"object\":{\"id\":\"cs_1\",\"amount_total\":2000,\"currency\":\"eur\","
        + "\"payment_status\":\"paid\",\"metadata\":{\"invoice_id\":\"" + INVOICE + "\"}}}}")
        .getBytes(StandardCharsets.UTF_8);
    var signature = signature(secret, NOW.getEpochSecond(), body);

    var event = provider.verifyWebhook(
        new PaymentProvider.Merchant("acct_test", PaymentProvider.Environment.TEST), body, signature).block();
    assertEquals(PaymentProvider.State.SUCCEEDED, event.paymentState());
    assertEquals(INVOICE, event.invoiceId());

    StepVerifier.create(provider.verifyWebhook(
            new PaymentProvider.Merchant("acct_test", PaymentProvider.Environment.TEST), body, "t=bad,v1=bad"))
        .expectErrorSatisfies(error -> assertEquals(
            ProviderException.Reason.INVALID_WEBHOOK, ((ProviderException) error).reason()))
        .verify();
  }

  private static StripePaymentProvider.Configuration configuration(String secret) {
    return new StripePaymentProvider.Configuration(
        URI.create("https://api.stripe.com"), "sk_test_secret", secret,
        URI.create("https://taska.example/ok"), URI.create("https://taska.example/cancel"), 300);
  }

  private static String signature(String secret, long timestamp, byte[] body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    var signed = timestamp + "." + new String(body, StandardCharsets.UTF_8);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
  }
}
