package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcOAuthClient;
import java.net.URI;
import java.time.Duration;

@RestController
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class OAuthController {
    private final GatewayRequestExecutor executor;
    private final ru.taska.filter.RequestIdProvider requestIds;
    private final GrpcOAuthClient client;
    private final ru.taska.security.SignupClientAddress addresses;
    @Value("${taska.oauth.ui-base-url:${TASKA_OAUTH_UI_BASE_URL:https://taska.ozero.dev}}") private String uiBase;
    @Value("${taska.oauth.issuer:${OAUTH_ISSUER:https://oauth.taska.ozero.dev}}") private String oauthIssuer;
    @jakarta.annotation.PostConstruct
    void validateUiBase() {
        if (oauthIssuer == null || oauthIssuer.isBlank()) oauthIssuer = "https://oauth.taska.ozero.dev";
        URI issuer = URI.create(oauthIssuer);
        if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                || issuer.getQuery() != null || issuer.getFragment() != null || !(issuer.getPath().isEmpty() || issuer.getPath().equals("/")))
            throw new IllegalArgumentException("OAuth issuer must be a canonical HTTPS origin");
        URI uri = URI.create(uiBase);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) throw new IllegalArgumentException("OAuth UI base URL must use HTTPS");
    }
    private static final String COOKIE_PREFIX = "__Host-taska_oauth_";

    @GetMapping("/api/v1/auth/oauth/{provider}/start")
    public Mono<ResponseEntity<Void>> start(@PathVariable String provider, @RequestParam(defaultValue = "/projects") String returnTo,
            @RequestParam(defaultValue = "ui") String target, @RequestParam(defaultValue = "") String binding, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        boolean oauthTarget = "oauth".equals(target);
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> ("ui".equals(target) && binding.isEmpty()
                ? client.start(provider, returnTo, ip, context) : client.start(provider, returnTo, ip, context, target, binding)).map(result -> {
            // The suffix only selects a fixed error page. Database target+binding remain authoritative.
            String cookie = cookie(result.getState(), result.getBrowserToken() + (oauthTarget ? ".oauth" : ""), Duration.ofMinutes(10)).toString();
            return redirect(result.getAuthorizationUrl()).header(HttpHeaders.SET_COOKIE, cookie).<Void>build();
        })).onErrorResume(error -> Mono.just(errorResponse(error, exchange, null, oauthTarget)));
    }
    @GetMapping("/api/v1/auth/oauth/{provider}/callback")
    public Mono<ResponseEntity<Void>> callbackGet(@PathVariable String provider, @RequestParam MultiValueMap<String, String> query, ServerWebExchange exchange) {
        // Wrong callback methods still consume the IP budget, without claiming a legitimate state.
        if (provider.equals("apple")) return callback(provider, new org.springframework.util.LinkedMultiValueMap<>(), exchange);
        return callback(provider, query, exchange);
    }
    @PostMapping(value = "/api/v1/auth/oauth/{provider}/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> callbackPost(@PathVariable String provider, ServerWebExchange exchange) {
        if (!provider.equals("apple")) return callback(provider, new org.springframework.util.LinkedMultiValueMap<>(), exchange);
        return exchange.getFormData().flatMap(form -> callback(provider, form, exchange));
    }
    private Mono<ResponseEntity<Void>> callback(String provider, MultiValueMap<String, String> values, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        String state = values.getFirst("state");
        var binding = validState(state) ? exchange.getRequest().getCookies().getFirst(COOKIE_PREFIX + state) : null;
        String cookieValue = binding == null ? "" : binding.getValue();
        boolean oauthTarget = cookieValue.length() == 49 && cookieValue.endsWith(".oauth");
        String browser = oauthTarget ? cookieValue.substring(0,43) : cookieValue;
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> client.callback(provider, state, browser, values.getFirst("code"), values.getFirst("error"), values.getFirst("user"), ip, context)
                .map(url -> {
                    var response = redirect(callbackLocation(url, oauthTarget));
                    if (validState(state)) response.header(HttpHeaders.SET_COOKIE, cookie(state, "", Duration.ZERO).toString());
                    return response.<Void>build();
                })).onErrorResume(error -> Mono.just(errorResponse(error, exchange, state, oauthTarget)));
    }
    @PostMapping("/api/v1/auth/oauth/exchange")
    public Mono<ResponseEntity<OAuthExchangeResponseDto>> exchange(@RequestBody Mono<OAuthExchangeRequestDto> request, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> request.flatMap(body -> client.exchange(body, ip, context)).map(body -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body)));
    }
    @PostMapping("/api/v1/auth/oauth/signup")
    public Mono<ResponseEntity<LoginResponseDto>> signup(@RequestBody Mono<OAuthSignupRequestDto> request, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> request.flatMap(body -> client.signup(body, ip, context)).map(body -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body)));
    }
    private String clientIp(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        return addresses.resolve(exchange);
    }
    private boolean validState(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    private ResponseCookie cookie(String state, String value, Duration age) {
        return ResponseCookie.from(COOKIE_PREFIX + state, value).path("/").secure(true).httpOnly(true).sameSite("None").maxAge(age).build();
    }
    private ResponseEntity.BodyBuilder redirect(String url) {
        return ResponseEntity.status(302).location(URI.create(url)).header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer");
    }
    private String callbackLocation(String url, boolean oauthTarget) {
        // A missing/expired database state has no authoritative target. The cookie hint may
        // reroute only an error to a fixed origin; it never changes a successful code's target.
        String uiError = URI.create(uiBase).resolve("/auth/callback").toString() + "?error=";
        if (oauthTarget && url.startsWith(uiError))
            return URI.create(oauthIssuer).resolve("/login/provider/callback") + "?" + URI.create(url).getRawQuery();
        return url;
    }
    private ResponseEntity<Void> errorResponse(Throwable error, ServerWebExchange exchange, String state, boolean oauthTarget) {
        var trailers = io.grpc.Status.trailersFromThrowable(error);
        var details = trailers == null ? java.util.Map.<String, String>of() : mapper.GrpcErrorDetails.decode(trailers);
        boolean limited = io.grpc.Status.fromThrowable(error).getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED;
        String reason = limited ? "RATE_LIMITED" : details.getOrDefault("reason", "PROVIDER_UNAVAILABLE");
        if (!java.util.Set.of("PROVIDER_CANCELLED", "STATE_INVALID", "EMAIL_NOT_VERIFIED", "ACCOUNT_BLOCKED",
                "ACCOUNT_LOCKED", "SIGNUP_DISABLED", "PROVIDER_UNAVAILABLE", "RATE_LIMITED").contains(reason)) reason = "PROVIDER_UNAVAILABLE";
        String requestId = requestIds.resolve(exchange);
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        // Navigation failures, including unavailable infrastructure, must return the browser to the UI.
        log.warn("[{}] OAuth navigation failed: {}", requestId, reason);
        var uri = org.springframework.web.util.UriComponentsBuilder.fromUri(URI.create(oauthTarget ? oauthIssuer : uiBase).resolve(oauthTarget ? "/login/provider/callback" : "/auth/callback"))
                .queryParam("error", reason).queryParam("requestId", requestId);
        String retry = details.get("retryAfterSeconds");
        if (limited && retry != null && retry.matches("[1-9][0-9]{0,9}")) uri.queryParam("retryAfter", retry);
        var response = redirect(uri.build().encode().toUriString());
        // OAuthGrpcService commits the quota reservation before claimState; retain the binding on quota rejection.
        if (!limited && validState(state)) response.header(HttpHeaders.SET_COOKIE, cookie(state, "", Duration.ZERO).toString());
        return response.build();
    }
}
