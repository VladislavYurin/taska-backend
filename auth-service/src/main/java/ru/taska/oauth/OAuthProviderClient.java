package ru.taska.oauth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import ru.taska.util.DirectoryInput;
import ru.taska.util.DisplayNames;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class OAuthProviderClient {
    private final OAuthProperties properties;
    private final OAuthProviderCatalog providers;
    private final ObjectMapper json;
    private final DirectoryInput input;
    private final Clock clock;
    private record CachedKeys(JwkSet value, java.time.Instant expiresAt) { }
    private final Map<String, CachedKeys> keys = new ConcurrentHashMap<>();

    public String callbackUri(String provider) { return OAuthUrls.endpoint(properties.getApiBaseUrl(), "/api/v1/auth/oauth/" + provider + "/callback"); }
    public String authorizationUrl(String provider, String state, String nonce, String verifier) {
        providers.require(provider);
        var client = properties.client(provider);
        String scope = switch (provider) { case "google" -> "openid email profile"; case "github" -> "read:user user:email"; default -> "name email"; };
        String url = client.getAuthorizationUri() + "?response_type=code&client_id=" + OAuthUrls.encode(client.getClientId())
                + "&redirect_uri=" + OAuthUrls.encode(callbackUri(provider)) + "&scope=" + OAuthUrls.encode(scope) + "&state=" + state;
        if (!provider.equals("github")) url += "&nonce=" + nonce;
        if (provider.equals("apple")) url += "&response_mode=form_post";
        else url += "&code_challenge_method=S256&code_challenge=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(org.apache.commons.codec.digest.DigestUtils.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        return url;
    }
    public Mono<OAuthIdentity> authenticate(String provider, OAuthFlowStore.State state, String code, String appleUser) {
        return Mono.defer(() -> {
            providers.require(provider);
            if (code == null || code.isBlank() || code.length() > 4096) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE");
            var client = properties.client(provider);
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code"); form.add("code", code); form.add("client_id", client.getClientId());
            form.add("redirect_uri", callbackUri(provider));
            form.add("client_secret", provider.equals("apple") ? appleSecret(client) : client.getClientSecret());
            if (!provider.equals("apple")) form.add("code_verifier", state.verifier());
            return http().post().uri(client.getTokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(BodyInserters.fromFormData(form)).exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE")));
                        return response.bodyToMono(String.class).map(json::readTree);
                    }).flatMap(token -> provider.equals("github") ? github(client, token) : oidc(provider, client, token, state.nonce(), appleUser));
        }).timeout(properties.getTimeout().multipliedBy(4)).switchIfEmpty(Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE")))
                .onErrorMap(error -> error instanceof ru.taska.exception.DomainException ? error : OAuthErrors.failure("PROVIDER_UNAVAILABLE"));
    }
    private Mono<OAuthIdentity> github(OAuthProperties.Client client, JsonNode token) {
        String access = text(token, "access_token");
        if (access == null) return Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE"));
        return get(client.getUserUri(), access).zipWith(get(client.getEmailsUri(), access)).map(data -> {
            JsonNode user = data.getT1();
            String id = text(user, "id");
            if (id == null || !id.matches("[0-9]{1,32}")) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE");
            for (JsonNode email : data.getT2()) {
                if (email.path("primary").asBoolean(false) && email.path("verified").asBoolean(false))
                    return identity("github", id, text(email, "email"), suggestion(text(user, "name")));
            }
            throw OAuthErrors.failure("EMAIL_NOT_VERIFIED");
        });
    }
    private Mono<JsonNode> get(String url, String token) {
        return http().get().uri(url).headers(headers -> {
            headers.setBearerAuth(token); headers.set("User-Agent", "Taska"); headers.set("X-GitHub-Api-Version", "2022-11-28");
        }).accept(MediaType.APPLICATION_JSON).exchangeToMono(response -> {
            if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE")));
            return response.bodyToMono(String.class).map(json::readTree);
        });
    }
    private Mono<OAuthIdentity> oidc(String provider, OAuthProperties.Client client, JsonNode token, String nonce, String appleUser) {
        String raw = text(token, "id_token");
        if (raw == null || raw.length() > 16384) return Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE"));
        var entry = keys.get(provider);
        Mono<JwkSet> cached = entry != null && entry.expiresAt().isAfter(clock.instant())
                ? Mono.just(entry.value()) : refreshKeys(provider, client);
        // A fresh JWKS fetch also handles provider signing-key rotation. No token-supplied URL is followed.
        return cached.flatMap(jwks -> verify(raw, jwks, client, nonce, provider))
                .onErrorResume(error -> refreshKeys(provider, client).flatMap(jwks -> verify(raw, jwks, client, nonce, provider)))
                .map(claims -> {
                    if (!(Boolean.TRUE.equals(claims.get("email_verified")) || "true".equals(claims.get("email_verified"))))
                        throw OAuthErrors.failure("EMAIL_NOT_VERIFIED");
                    String name = provider.equals("apple") ? appleName(appleUser) : suggestion(claims.get("name", String.class));
                    return identity(provider, claims.getSubject(), claims.get("email", String.class), name);
                });
    }
    private Mono<Claims> verify(String raw, JwkSet jwks, OAuthProperties.Client client, String nonce, String provider) {
        return Mono.fromCallable(() -> {
            var signed = Jwts.parser().clock(() -> Date.from(clock.instant())).requireAudience(client.getClientId()).require("nonce", nonce)
                    .keyLocator(header -> {
                        if (!"RS256".equals(header.getAlgorithm()) || !(header.get("kid") instanceof String kid) || kid.length() > 128)
                            throw OAuthErrors.failure("PROVIDER_UNAVAILABLE");
                        return jwks.getKeys().stream().filter(key -> key instanceof PublicJwk<?> && kid.equals(key.getId())
                                        && (key.getAlgorithm() == null || "RS256".equals(key.getAlgorithm()))
                                        && (key.get("use") == null || "sig".equals(key.get("use"))))
                                .map(key -> (PublicKey) key.toKey()).findFirst().orElseThrow(() -> OAuthErrors.failure("PROVIDER_UNAVAILABLE"));
                    }).build().parseSignedClaims(raw);
            Claims claims = signed.getPayload();
            boolean issuer = provider.equals("google") ? java.util.Set.of("accounts.google.com", "https://accounts.google.com").contains(claims.getIssuer())
                    : "https://appleid.apple.com".equals(claims.getIssuer());
            if (!java.util.Set.of(client.getClientId()).equals(claims.getAudience()) || !issuer || claims.getExpiration() == null || claims.getSubject() == null || claims.getSubject().isBlank() || claims.getSubject().length() > 512)
                throw OAuthErrors.failure("PROVIDER_UNAVAILABLE");
            return claims;
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private Mono<JwkSet> refreshKeys(String provider, OAuthProperties.Client client) {
        return fetchKeys(client).doOnNext(value -> keys.put(provider, new CachedKeys(value, clock.instant().plus(Duration.ofMinutes(5)))));
    }
    private Mono<JwkSet> fetchKeys(OAuthProperties.Client client) {
        return http().get().uri(client.getJwksUri()).exchangeToMono(response -> {
            if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE")));
            return response.bodyToMono(String.class).map(value -> Jwks.setParser().build().parse(value));
        });
    }
    String appleSecret(OAuthProperties.Client client) {
        try {
            String pem = client.getPrivateKey().replace("\\n", "\n").replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            var key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return Jwts.builder().header().keyId(client.getKeyId()).and().issuer(client.getTeamId()).subject(client.getClientId())
                    .audience().add("https://appleid.apple.com").and().issuedAt(Date.from(clock.instant()))
                    .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(5)))).signWith(key, Jwts.SIG.ES256).compact();
        } catch (Exception ignored) { throw OAuthErrors.failure("PROVIDER_UNAVAILABLE"); }
    }
    private OAuthIdentity identity(String provider, String subject, String email, String name) {
        if (email == null || email.isBlank()) throw OAuthErrors.failure("EMAIL_NOT_VERIFIED");
        try { return new OAuthIdentity(provider, subject, input.email(email), name); }
        catch (ru.taska.exception.DomainException ignored) { throw OAuthErrors.failure("EMAIL_NOT_VERIFIED"); }
    }
    private String appleName(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) return null;
        try {
            JsonNode name = json.readTree(raw).path("name");
            String first = text(name, "firstName"), last = text(name, "lastName");
            return suggestion((first == null ? "" : first) + " " + (last == null ? "" : last));
        } catch (Exception ignored) { return null; }
    }
    private String suggestion(String value) {
        try { return DisplayNames.normalize(value); } catch (ru.taska.exception.DomainException ignored) { return null; }
    }
    private String text(JsonNode value, String key) {
        JsonNode node = value.get(key); return node == null || node.isNull() ? null : node.asString();
    }
    private WebClient http() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getTimeout().toMillis()).responseTimeout(properties.getTimeout())))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(65536)).build();
    }
}
