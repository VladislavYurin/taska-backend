package ru.taska.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP token, user/email and JWKS endpoints; no network calls leave localhost. */
final class FakeOAuthProvider implements AutoCloseable {
    final KeyPair signing = Jwts.SIG.RS256.keyPair().build();
    final KeyPair appleClient = Jwts.SIG.ES256.keyPair().build();
    final AtomicInteger tokenCalls = new AtomicInteger();
    final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    final Map<String, Ticket> access = new ConcurrentHashMap<>();
    final HttpServer server;
    final JsonMapper json = JsonMapper.builder().build();
    volatile Throwable failure;
    record Ticket(String provider, String subject, String email, String nonce, String challenge,
                  boolean verified, boolean primary, String issuer, String audience, Instant expiry,
                  String name, KeyPair signingKey) { }

    FakeOAuthProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> handle(exchange, () -> token(exchange)));
        server.createContext("/jwks", exchange -> handle(exchange, () -> json.writeValueAsString(Map.of("keys", List.of(
                json.readTree(Jwks.json(Jwks.builder().key(signing.getPublic()).id("test-key").build())))))));
        server.createContext("/user/emails", exchange -> handle(exchange, () -> {
            var ticket = access.get(bearer(exchange));
            return json.writeValueAsString(List.of(Map.of("email", ticket.email(), "primary", ticket.primary(), "verified", ticket.verified())));
        }));
        server.createContext("/user", exchange -> handle(exchange, () -> {
            var ticket = access.get(bearer(exchange));
            return json.writeValueAsString(Map.of("id", Long.parseLong(ticket.subject()), "login", "mutable-login-ignored", "name", ticket.name()));
        }));
        server.start();
    }
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String applePrivateKey() { return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(appleClient.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----"; }
    void configure(OAuthProperties properties) {
        properties.setApiBaseUrl("https://api.example.test"); properties.setUiBaseUrl("https://ui.example.test");
        for (String provider : List.of("google", "github", "apple")) {
            var client = properties.client(provider);
            client.setEnabled(true); client.setClientId(provider + "-client"); client.setClientSecret("never-log-" + provider + "-secret");
            client.setAuthorizationUri(base() + "/authorize"); client.setTokenUri(base() + "/token"); client.setJwksUri(base() + "/jwks");
            client.setUserUri(base() + "/user"); client.setEmailsUri(base() + "/user/emails");
        }
        properties.getApple().setPrivateKey(applePrivateKey()); properties.getApple().setTeamId("test-team"); properties.getApple().setKeyId("apple-key");
    }
    String issue(String provider, String subject, String email, Map<String, String> authorization) {
        String code = "never-log-provider-code-" + UUID.randomUUID();
        tickets.put(code, new Ticket(provider, subject, email, authorization.get("nonce"), authorization.get("code_challenge"),
                true, true, provider.equals("apple") ? "https://appleid.apple.com" : "https://accounts.google.com", provider + "-client",
                Instant.now().plusSeconds(300), "Provider Name", signing));
        return code;
    }
    String token(HttpExchange exchange) throws Exception {
        tokenCalls.incrementAndGet();
        var form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var ticket = tickets.remove(form.get("code"));
        assertThat(ticket).isNotNull();
        assertThat(form.get("grant_type")).isEqualTo("authorization_code");
        assertThat(form.get("client_id")).isEqualTo(ticket.provider() + "-client");
        assertThat(form.get("redirect_uri")).isEqualTo("https://api.example.test/api/v1/auth/oauth/" + ticket.provider() + "/callback");
        if (ticket.provider().equals("apple")) {
            var jwt = Jwts.parser().verifyWith(appleClient.getPublic()).build().parseSignedClaims(form.get("client_secret"));
            assertThat(jwt.getHeader().getAlgorithm()).isEqualTo("ES256");
            assertThat(jwt.getHeader().getKeyId()).isEqualTo("apple-key");
            assertThat(jwt.getPayload().getIssuer()).isEqualTo("test-team");
            assertThat(jwt.getPayload().getSubject()).isEqualTo("apple-client");
            assertThat(jwt.getPayload().getAudience()).containsExactly("https://appleid.apple.com");
            assertThat(jwt.getPayload().getExpiration().toInstant()).isBefore(Instant.now().plusSeconds(301));
        } else {
            assertThat(form.get("client_secret")).isEqualTo("never-log-" + ticket.provider() + "-secret");
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(org.apache.commons.codec.digest.DigestUtils.sha256(form.get("code_verifier")));
            assertThat(challenge).isEqualTo(ticket.challenge());
        }
        if (ticket.provider().equals("github")) {
            String raw = "never-log-access-" + UUID.randomUUID(); access.put(raw, ticket);
            return json.writeValueAsString(Map.of("access_token", raw, "token_type", "bearer"));
        }
        var builder = Jwts.builder().header().keyId("test-key").and().subject(ticket.subject()).issuer(ticket.issuer())
                .audience().add(ticket.audience()).and().issuedAt(Date.from(Instant.now())).claim("nonce", ticket.nonce())
                .claim("email", ticket.email()).claim("email_verified", ticket.provider().equals("apple") ? String.valueOf(ticket.verified()) : ticket.verified())
                .claim("name", ticket.name());
        if (ticket.expiry() != null) builder.expiration(Date.from(ticket.expiry()));
        return json.writeValueAsString(Map.of("id_token", builder.signWith(ticket.signingKey().getPrivate(), Jwts.SIG.RS256).compact()));
    }
    void assertHealthy() { if (failure != null) throw new AssertionError("Fake provider contract failed", failure); }
    static Map<String, String> query(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null) return values;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }
    private String bearer(HttpExchange exchange) { return exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()); }
    private interface Reply { String get() throws Exception; }
    private void handle(HttpExchange exchange, Reply reply) {
        try {
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Throwable error) {
            failure = error;
            try { exchange.sendResponseHeaders(500, 0); } catch (Exception ignored) { }
        } finally { exchange.close(); }
    }
    @Override public void close() { server.stop(0); }
}
