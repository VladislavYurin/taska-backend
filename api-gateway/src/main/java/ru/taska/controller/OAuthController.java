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
@RequiredArgsConstructor
public class OAuthController {
    private final GatewayRequestExecutor executor;
    private final GrpcOAuthClient client;
    private final ru.taska.security.SignupClientAddress addresses;
    @Value("${taska.oauth.ui-base-url:${TASKA_OAUTH_UI_BASE_URL:https://taska.ozero.dev}}") private String uiBase;
    @jakarta.annotation.PostConstruct
    void validateUiBase() {
        URI uri = URI.create(uiBase);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) throw new IllegalArgumentException("OAuth UI base URL must use HTTPS");
    }
    private static final String COOKIE_PREFIX = "__Host-taska_oauth_";

    @GetMapping("/api/v1/auth/oauth/{provider}/start")
    public Mono<ResponseEntity<Void>> start(@PathVariable String provider, @RequestParam(defaultValue = "/projects") String returnTo, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> client.start(provider, returnTo, ip, context).map(result -> {
            String cookie = cookie(result.getState(), result.getBrowserToken(), Duration.ofMinutes(10)).toString();
            return redirect(result.getAuthorizationUrl()).header(HttpHeaders.SET_COOKIE, cookie).build();
        }));
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
        String browser = binding == null ? "" : binding.getValue();
        return executor.execute(exchange, EndpointSecurity.PUBLIC, context -> client.callback(provider, state, browser, values.getFirst("code"), values.getFirst("error"), values.getFirst("user"), ip, context)
                .onErrorResume(error -> io.grpc.Status.fromThrowable(error).getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED
                        ? Mono.error(error) : Mono.just(errorRedirect("PROVIDER_UNAVAILABLE")))
                .map(url -> {
                    var response = redirect(url);
                    if (validState(state)) response.header(HttpHeaders.SET_COOKIE, cookie(state, "", Duration.ZERO).toString());
                    return response.build();
                }));
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
    private String errorRedirect(String reason) { return URI.create(uiBase).resolve("/auth/callback?error=" + reason).toString(); }
}
