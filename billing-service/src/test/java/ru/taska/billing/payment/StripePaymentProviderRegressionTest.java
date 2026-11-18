package ru.taska.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.billing.ingress.ProviderWebhookRegistry;
import tools.jackson.databind.ObjectMapper;

/** Permanent regressions for the TAS-348 review, using local provider responses only. */
class StripePaymentProviderRegressionTest {
  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
  private static final UUID INVOICE = UUID.fromString("94e2fe65-8b2c-4a9f-a588-e1e23ba5334a");
  private static final PaymentProvider.Merchant MERCHANT =
      new PaymentProvider.Merchant("acct_test", PaymentProvider.Environment.TEST);

  @Test
  void enabledConfigurationRegistersTheSameAdapterForCheckoutAndWebhook() {
    new ApplicationContextRunner()
        .withUserConfiguration(StripePaymentProviderConfiguration.class)
        .withBean(WebClient.Builder.class, WebClient::builder)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "taska.billing.stripe.enabled=true",
            "TASKA_BILLING_STRIPE_API_KEY=sk_test_fixture",
            "TASKA_BILLING_STRIPE_WEBHOOK_SECRET=whsec_fixture",
            "TASKA_BILLING_STRIPE_SUCCESS_URL=https://taska.example/ok",
            "TASKA_BILLING_STRIPE_CANCEL_URL=https://taska.example/cancel",
            "TASKA_BILLING_STRIPE_ALLOWED_REDIRECT_HOSTS=taska.example",
            "TASKA_BILLING_STRIPE_MERCHANT_ACCOUNT=acct_test",
            "TASKA_BILLING_STRIPE_PROFILE_VERSION=fixture-v1")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(PaymentProviderRegistry.class)
              .hasSingleBean(ProviderWebhookRegistry.class);
          var identity = new ProviderWebhookRegistry.Identity(
              "stripe", "acct_test", PaymentProvider.Environment.TEST);
          assertThat(context.getBean(ProviderWebhookRegistry.class).require(identity).profileVersions())
              .containsExactly("fixture-v1");
        });
  }

  @Test
  void lookupUsesProviderOccurrenceAndNeverTheLocalObservationTime() {
    var paths = new ArrayList<String>();
    var provider = provider(paths, session("complete", "paid", ",\"payment_intent\":\"pi_1\""),
        "{\"id\":\"pi_1\",\"created\":1794311000}");
    var payment = provider.fetchPayment(MERCHANT, "cs_1").block();
    assertEquals(PaymentProvider.State.SUCCEEDED, payment.state());
    assertEquals(Instant.ofEpochSecond(1794311000), payment.occurredAt());
    assertThat(paths).containsExactly("/v1/checkout/sessions/cs_1", "/v1/payment_intents/pi_1");
  }

  @Test
  void missingPaymentIntentIsUnknownAndDoesNotInventAnOccurrence() {
    var paths = new ArrayList<String>();
    var provider = provider(paths, session("complete", "paid", ""));
    StepVerifier.create(provider.fetchPayment(MERCHANT, "cs_1"))
        .expectErrorSatisfies(error -> assertEquals(ProviderException.Reason.UNKNOWN_OUTCOME,
            ((ProviderException) error).reason())).verify();
    assertThat(paths).containsExactly("/v1/checkout/sessions/cs_1");
  }

  @ParameterizedTest
  @CsvSource({"expired,DECLINED", "open,PENDING", "complete,PENDING"})
  void unpaidCheckoutDistinguishesExpiredFromOpenAndAsync(String status, PaymentProvider.State state) {
    var paths = new ArrayList<String>();
    var payment = provider(paths, session(status, "unpaid", ""))
        .fetchPayment(MERCHANT, "cs_1").block();
    assertEquals(state, payment.state());
    assertThat(payment.occurredAt()).isNull();
    assertThat(paths).containsExactly("/v1/checkout/sessions/cs_1");
  }

  @ParameterizedTest
  @CsvSource({"succeeded,SUCCEEDED", "failed,DECLINED", "canceled,DECLINED",
      "pending,PENDING", "requires_action,PENDING", "new_status,UNKNOWN"})
  void refundPreservesTerminalAndUncertainProviderStates(String status, PaymentProvider.RefundState state) {
    var paths = new ArrayList<String>();
    var provider = provider(paths, "{\"payment_intent\":\"pi_1\"}",
        "{\"id\":\"re_1\",\"status\":\"" + status + "\"}");
    var refund = provider.refund(MERCHANT, "cs_1", new PaymentProvider.Money(2000, "EUR"),
        UUID.randomUUID(), "owned-refund-key").block();
    assertEquals(state, refund.state());
    assertEquals("owned-refund-key", refund.providerIdempotencyKey());
    assertThat(paths).containsExactly("/v1/checkout/sessions/cs_1", "/v1/refunds");
  }

  @Test
  void signatureRotationAcceptsAnyMatchingV1AndStillRejectsExpiredOrAlteredBodies() throws Exception {
    var provider = provider(new ArrayList<>());
    byte[] body = event("checkout.session.completed", "paid");
    String valid = signature(NOW.getEpochSecond(), body);
    String rotated = valid.replace(",v1=", ",v1=old-signature,v1=");
    assertEquals(PaymentProvider.State.SUCCEEDED,
        provider.verifyWebhook(MERCHANT, body, rotated).block().paymentState());
    for (String bad : List.of(signature(NOW.minusSeconds(301).getEpochSecond(), body),
        signature(NOW.plusSeconds(301).getEpochSecond(), body), "t=bad,v1=bad")) {
      invalidWebhook(provider, body, bad);
    }
    invalidWebhook(provider,
        new String(body, StandardCharsets.UTF_8).replace("2000", "3000").getBytes(StandardCharsets.UTF_8),
        rotated);
  }

  @ParameterizedTest
  @CsvSource({"checkout.session.async_payment_failed,unpaid,DECLINED",
      "checkout.session.async_payment_succeeded,unpaid,PENDING",
      "checkout.session.async_payment_succeeded,paid,SUCCEEDED"})
  void signedAsyncWebhookRetainsTheActualPaymentState(
      String kind, String status, PaymentProvider.State state) throws Exception {
    byte[] body = event(kind, status);
    assertEquals(state, provider(new ArrayList<>()).verifyWebhook(
        MERCHANT, body, signature(NOW.getEpochSecond(), body)).block().paymentState());
  }

  private static void invalidWebhook(StripePaymentProvider provider, byte[] body, String signature) {
    StepVerifier.create(provider.verifyWebhook(MERCHANT, body, signature))
        .expectErrorSatisfies(error -> assertEquals(ProviderException.Reason.INVALID_WEBHOOK,
            ((ProviderException) error).reason())).verify();
  }

  private static StripePaymentProvider provider(List<String> paths, String... responses) {
    var builder = WebClient.builder().exchangeFunction(request -> {
      int index = paths.size();
      paths.add(request.url().getPath());
      assertThat(index).isLessThan(responses.length);
      return Mono.just(ClientResponse.create(HttpStatus.OK)
          .header("Content-Type", "application/json").body(responses[index]).build());
    });
    return new StripePaymentProvider(builder,
        new StripePaymentProvider.Configuration(java.net.URI.create("https://api.stripe.com"),
            "sk_test_fixture", "whsec_fixture", java.net.URI.create("https://taska.example/ok"),
            java.net.URI.create("https://taska.example/cancel"), java.util.Set.of("taska.example"), 300),
        new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static String session(String status, String paymentStatus, String extra) {
    return "{\"id\":\"cs_1\",\"status\":\"" + status + "\",\"payment_status\":\"" + paymentStatus
        + "\",\"amount_total\":2000,\"currency\":\"eur\",\"metadata\":{\"invoice_id\":\"" + INVOICE
        + "\"}" + extra + "}";
  }

  private static byte[] event(String type, String status) {
    return ("{\"id\":\"evt_1\",\"created\":" + NOW.getEpochSecond() + ",\"type\":\"" + type
        + "\",\"data\":{\"object\":{\"id\":\"cs_1\",\"amount_total\":2000,\"currency\":\"eur\","
        + "\"payment_status\":\"" + status + "\",\"metadata\":{\"invoice_id\":\"" + INVOICE
        + "\",\"amount_minor\":\"2000\",\"currency\":\"EUR\",\"merchant_account\":\"acct_test\"}}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String signature(long timestamp, byte[] body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_fixture".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] signed = (timestamp + "." + new String(body, StandardCharsets.UTF_8))
        .getBytes(StandardCharsets.UTF_8);
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signed));
  }
}
