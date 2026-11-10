package ru.taska.billing.payment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stripe hosted Checkout adapter. Credentials never cross the PaymentProvider boundary. */
public final class StripePaymentProvider implements PaymentProvider {
  private static final String STRIPE = "stripe";
  private static final String TEST_KEY_PREFIX = "sk_test_";

  public record Configuration(
      URI endpoint,
      String apiKey,
      String webhookSecret,
      URI successUrl,
      URI cancelUrl,
      long webhookToleranceSeconds) {
    public Configuration {
      Objects.requireNonNull(endpoint);
      PaymentProvider.require(apiKey, "apiKey");
      PaymentProvider.require(webhookSecret, "webhookSecret");
      Objects.requireNonNull(successUrl);
      Objects.requireNonNull(cancelUrl);
      if (!apiKey.startsWith(TEST_KEY_PREFIX))
        throw new IllegalArgumentException("Stripe live credentials are disabled for sandbox");
      if (webhookToleranceSeconds <= 0) throw new IllegalArgumentException("invalid tolerance");
      validateRedirect(successUrl);
      validateRedirect(cancelUrl);
    }

    private static void validateRedirect(URI value) {
      if (!("https".equalsIgnoreCase(value.getScheme())
          || ("http".equalsIgnoreCase(value.getScheme()) && "localhost".equals(value.getHost()))))
        throw new IllegalArgumentException("redirect URL is not allowlisted");
    }
  }

  private final WebClient client;
  private final Configuration configuration;
  private final ObjectMapper json;
  private final Clock clock;

  public StripePaymentProvider(
      WebClient.Builder builder, Configuration configuration, ObjectMapper json, Clock clock) {
    this.configuration = Objects.requireNonNull(configuration);
    this.json = Objects.requireNonNull(json);
    this.clock = Objects.requireNonNull(clock);
    this.client = builder.baseUrl(configuration.endpoint().toString()).build();
  }

  @Override
  public Mono<Capabilities> capabilities(Merchant merchant) {
    return Mono.fromSupplier(() -> {
      requireTest(merchant);
      return new Capabilities(true, true, true, true, false, true);
    });
  }

  @Override
  public Mono<Checkout> createCheckout(
      Merchant merchant, Invoice invoice, UUID operationId, String providerIdempotencyKey) {
    requireTest(merchant);
    Objects.requireNonNull(invoice);
    Objects.requireNonNull(operationId);
    PaymentProvider.require(providerIdempotencyKey, "providerIdempotencyKey");
    var form =
        Map.of(
            "mode", "payment",
            "line_items[0][price_data][currency]", invoice.total().currency().toLowerCase(),
            "line_items[0][price_data][product_data][name]", "Taska invoice " + invoice.invoiceId(),
            "line_items[0][price_data][unit_amount]", Long.toString(invoice.total().amountMinor()),
            "line_items[0][quantity]", "1",
            "success_url", configuration.successUrl().toString(),
            "cancel_url", configuration.cancelUrl().toString(),
            "client_reference_id", invoice.invoiceId().toString(),
            "metadata[invoice_id]", invoice.invoiceId().toString(),
            "metadata[operation_id]", operationId.toString());
    return request(HttpMethod.POST, "/v1/checkout/sessions", providerIdempotencyKey, form)
        .map(body -> {
          var id = text(body, "id");
          var url = text(body, "url");
          return new Checkout(id, url, state(body), providerIdempotencyKey);
        });
  }

  @Override
  public Mono<Payment> fetchPayment(Merchant merchant, String providerPaymentId) {
    requireTest(merchant);
    PaymentProvider.require(providerPaymentId, "providerPaymentId");
    return request(HttpMethod.GET, "/v1/checkout/sessions/" + encode(providerPaymentId), null, Map.of())
        .map(body -> new Payment(
            text(body, "id"), UUID.fromString(text(body.path("metadata"), "invoice_id")),
            new Money(body.path("amount_total").longValue(), body.path("currency").asText().toUpperCase()),
            state(body)));
  }

  @Override
  public Mono<Refund> refund(
      Merchant merchant, String providerPaymentId, Money amount, UUID operationId, String key) {
    requireTest(merchant);
    PaymentProvider.require(providerPaymentId, "providerPaymentId");
    PaymentProvider.require(key, "providerIdempotencyKey");
    return request(HttpMethod.GET, "/v1/checkout/sessions/" + encode(providerPaymentId), null, Map.of())
        .flatMap(session -> {
          var paymentIntent = text(session, "payment_intent");
          var form = Map.of("payment_intent", paymentIntent, "amount", Long.toString(amount.amountMinor()));
          return request(HttpMethod.POST, "/v1/refunds", key, form);
        })
        .map(body -> new Refund(text(body, "id"), providerPaymentId, amount,
            "succeeded".equals(body.path("status").asText()) ? RefundState.SUCCEEDED : RefundState.PENDING, key));
  }

  @Override
  public Mono<VerifiedEvent> verifyWebhook(Merchant merchant, byte[] rawBody, String signature) {
    requireTest(merchant);
    if (rawBody == null || signature == null || !validSignature(rawBody, signature))
      return Mono.error(new ProviderException(ProviderException.Reason.INVALID_WEBHOOK));
    return Mono.fromCallable(() -> json.readTree(rawBody)).map(root -> event(root, merchant));
  }

  private Mono<JsonNode> request(
      HttpMethod method, String path, String idempotencyKey, Map<String, String> values) {
    var body = values.entrySet().stream()
        .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
    var request = client.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiKey());
    if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
    var prepared = method == HttpMethod.POST
        ? request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromValue(body))
        : request;
    return prepared.retrieve()
        .onStatus(s -> s.value() == 404, response -> Mono.error(new ProviderException(ProviderException.Reason.NOT_FOUND)))
        .onStatus(s -> s.isError(), response -> Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .bodyToMono(String.class).flatMap(raw -> Mono.fromCallable(() -> json.readTree(raw)));
  }

  private boolean validSignature(byte[] body, String header) {
    try {
      long timestamp = Long.parseLong(part(header, "t"));
      if (Math.abs(clock.instant().getEpochSecond() - timestamp) > configuration.webhookToleranceSeconds()) return false;
      var signed = timestamp + "." + new String(body, StandardCharsets.UTF_8);
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(configuration.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      var expected = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
      return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), part(header, "v1").getBytes(StandardCharsets.US_ASCII));
    } catch (Exception e) { return false; }
  }

  private VerifiedEvent event(JsonNode root, Merchant merchant) {
    var object = root.path("data").path("object");
    var type = root.path("type").asText();
    var paymentId = text(object, "id");
    var invoice = UUID.fromString(text(object.path("metadata"), "invoice_id"));
    var amount = new Money(object.path("amount_total").longValue(), object.path("currency").asText().toUpperCase());
    var state = type.endsWith("failed") ? State.DECLINED : State.SUCCEEDED;
    return new VerifiedEvent(text(root, "id"), paymentId, EventKind.PAYMENT,
        merchant, invoice, amount,
        Instant.ofEpochSecond(root.path("created").longValue()), state, null, null);
  }

  private static State state(JsonNode body) {
    return switch (body.path("payment_status").asText()) {
      case "paid" -> State.SUCCEEDED;
      case "unpaid" -> State.PENDING;
      default -> State.UNKNOWN;
    };
  }

  private static String part(String header, String name) {
    return java.util.Arrays.stream(header.split(","))
        .map(String::trim).filter(p -> p.startsWith(name + "="))
        .map(p -> p.substring(name.length() + 1)).findFirst().orElse("");
  }

  private static String encode(String value) { return URI.create("http://localhost/" + value).getPath().substring(1); }
  private static String text(JsonNode node, String name) {
    return PaymentProvider.require(node.path(name).asText(), name);
  }
  private static void requireTest(Merchant merchant) {
    Objects.requireNonNull(merchant);
    if (merchant.environment() != Environment.TEST) throw new ProviderException(ProviderException.Reason.UNSUPPORTED_CAPABILITY);
  }
}
