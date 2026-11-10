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
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
      Set<String> allowedRedirectHosts,
      long webhookToleranceSeconds) {
    public Configuration {
      Objects.requireNonNull(endpoint);
      PaymentProvider.require(apiKey, "apiKey");
      PaymentProvider.require(webhookSecret, "webhookSecret");
      Objects.requireNonNull(successUrl);
      Objects.requireNonNull(cancelUrl);
      allowedRedirectHosts = Set.copyOf(allowedRedirectHosts);
      if (allowedRedirectHosts.isEmpty()) throw new IllegalArgumentException("redirect allowlist is empty");
      if (!apiKey.startsWith(TEST_KEY_PREFIX))
        throw new IllegalArgumentException("Stripe live credentials are disabled for sandbox");
      if (webhookToleranceSeconds <= 0) throw new IllegalArgumentException("invalid tolerance");
      validateRedirect(successUrl, allowedRedirectHosts);
      validateRedirect(cancelUrl, allowedRedirectHosts);
    }

    private static void validateRedirect(URI value, Set<String> allowedHosts) {
      if (!("https".equalsIgnoreCase(value.getScheme())
          || ("http".equalsIgnoreCase(value.getScheme()) && "localhost".equals(value.getHost())))
          || !allowedHosts.contains(value.getHost()))
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
        Map.ofEntries(
            Map.entry("mode", "payment"),
            Map.entry("line_items[0][price_data][currency]", invoice.total().currency().toLowerCase()),
            Map.entry("line_items[0][price_data][product_data][name]", "Taska invoice " + invoice.invoiceId()),
            Map.entry("line_items[0][price_data][unit_amount]", Long.toString(invoice.total().amountMinor())),
            Map.entry("line_items[0][quantity]", "1"),
            Map.entry("success_url", configuration.successUrl().toString()),
            Map.entry("cancel_url", configuration.cancelUrl().toString()),
            Map.entry("client_reference_id", invoice.invoiceId().toString()),
            Map.entry("metadata[invoice_id]", invoice.invoiceId().toString()),
            Map.entry("metadata[operation_id]", operationId.toString()),
            Map.entry("metadata[amount_minor]", Long.toString(invoice.total().amountMinor())),
            Map.entry("metadata[currency]", invoice.total().currency()),
            Map.entry("metadata[merchant_account]", merchant.accountId()));
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
        .map(body -> payment(body));
  }

  @Override
  public Mono<Refund> refund(
      Merchant merchant, String providerPaymentId, Money amount, UUID operationId, String key) {
    requireTest(merchant);
    PaymentProvider.require(providerPaymentId, "providerPaymentId");
    PaymentProvider.require(key, "providerIdempotencyKey");
    return request(HttpMethod.GET, "/v1/checkout/sessions/" + encode(providerPaymentId), null, Map.of())
        .flatMap(session -> {
          if (!session.hasNonNull("payment_intent"))
            return Mono.error(new ProviderException(ProviderException.Reason.NOT_FOUND));
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
    var request = client.method(method).uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiKey());
    if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    values.forEach(form::add);
    var prepared = method == HttpMethod.POST
        ? request.contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(form)
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
    if (!(type.equals("checkout.session.completed")
        || type.equals("checkout.session.async_payment_succeeded")
        || type.equals("checkout.session.async_payment_failed")))
      throw new ProviderException(ProviderException.Reason.INVALID_WEBHOOK);
    var paymentId = text(object, "id");
    var metadata = object.path("metadata");
    var invoice = parseUuid(metadata, "invoice_id");
    var amount = new Money(parseLong(metadata, "amount_minor"), text(metadata, "currency").toUpperCase());
    if (!merchant.accountId().equals(text(metadata, "merchant_account"))
        || object.path("amount_total").longValue() != amount.amountMinor()
        || !object.path("currency").asText().equalsIgnoreCase(amount.currency()))
      throw new ProviderException(ProviderException.Reason.INVALID_WEBHOOK);
    var state = type.endsWith("failed") ? State.DECLINED : paymentStatus(object);
    if (state == State.UNKNOWN) throw new ProviderException(ProviderException.Reason.INVALID_WEBHOOK);
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

  private static State paymentStatus(JsonNode body) {
    return switch (body.path("payment_status").asText()) {
      case "paid" -> State.SUCCEEDED;
      case "unpaid" -> State.PENDING;
      default -> State.UNKNOWN;
    };
  }

  private static Payment payment(JsonNode body) {
    try {
      var metadata = body.path("metadata");
      return new Payment(text(body, "id"), parseUuid(metadata, "invoice_id"),
          new Money(body.path("amount_total").longValue(), text(body, "currency").toUpperCase()), state(body));
    } catch (RuntimeException error) {
      throw new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME);
    }
  }

  private static UUID parseUuid(JsonNode node, String name) {
    try { return UUID.fromString(text(node, name)); }
    catch (RuntimeException error) { throw new ProviderException(ProviderException.Reason.INVALID_WEBHOOK); }
  }

  private static long parseLong(JsonNode node, String name) {
    try { return Long.parseLong(text(node, name)); }
    catch (RuntimeException error) { throw new ProviderException(ProviderException.Reason.INVALID_WEBHOOK); }
  }

  private static String part(String header, String name) {
    return java.util.Arrays.stream(header.split(","))
        .map(String::trim).filter(p -> p.startsWith(name + "="))
        .map(p -> p.substring(name.length() + 1)).findFirst().orElse("");
  }

  private static String encode(String value) {
    return org.springframework.web.util.UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
  }
  private static String text(JsonNode node, String name) {
    return PaymentProvider.require(node.path(name).asText(), name);
  }
  private static void requireTest(Merchant merchant) {
    Objects.requireNonNull(merchant);
    if (merchant.environment() != Environment.TEST) throw new ProviderException(ProviderException.Reason.UNSUPPORTED_CAPABILITY);
  }
}
