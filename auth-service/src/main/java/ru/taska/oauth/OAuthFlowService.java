package ru.taska.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.AuthResponseDto;
import ru.taska.util.DisplayNames;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OAuthFlowService {
    private final OAuthFlowStore store;
    private final OAuthProviderClient client;
    private final OAuthAccountService accounts;
    private final OAuthProperties properties;
    private final OAuthProviderCatalog providers;
    private final ru.taska.security.oauth.OAuthBridgeProperties bridge;
    public record Start(String authorizationUrl, String state, String browserToken) { }
    public record Exchange(AuthResponseDto session, String signupToken, OAuthIdentity identity, String returnTo) { }
    public Mono<Start> start(String provider, String returnTo) { return start(provider, returnTo, "ui", null); }
    public Mono<Start> start(String provider, String returnTo, String requestedTarget, String binding) {
        return Mono.defer(() -> {
            String target = target(requestedTarget, binding);
            provider(provider);
            String state = OAuthFlowStore.random(), browser = OAuthFlowStore.random(), nonce = OAuthFlowStore.random(), verifier = OAuthFlowStore.random();
            String url = client.authorizationUrl(provider, state, nonce, verifier);
            return store.saveState(state, browser, new OAuthFlowStore.State(provider, nonce, verifier, OAuthUrls.returnTo(returnTo), target, target.equals("oauth") ? binding : null))
                    .thenReturn(new Start(url, state, browser));
        });
    }
    public Mono<String> callback(String provider, String state, String browserToken, String code, String error, String appleUser) {
        return callback(provider, state, browserToken, code, error, appleUser, "");
    }
    public Mono<String> callback(String provider, String state, String browserToken, String code, String error, String appleUser, String requestId) {
        return Mono.defer(() -> {
            provider(provider);
            return store.claimState(provider, state, browserToken).flatMap(value -> Mono.defer(() -> {
                target(value.target(), value.binding());
                if (error != null && !error.isEmpty()) return Mono.<String>error(OAuthErrors.failure(error.equals("access_denied") || error.equals("user_cancelled_authorize") ? "PROVIDER_CANCELLED" : "PROVIDER_UNAVAILABLE"));
                return client.authenticate(provider, value, code, appleUser)
                        .flatMap(identity -> (value.target().equals("oauth") ? accounts.checkSignIn(identity) : accounts.check(identity)).then(store.grant("EXCHANGE", identity, value.returnTo(), value.target(), value.binding())))
                        .map(grant -> redirect(value.target(), "code", grant));
            }).onErrorResume(failure -> Mono.just(errorRedirect(value.target(), failure, requestId))));
        }).onErrorResume(failure -> Mono.just(errorRedirect("ui", failure, requestId)));
    }
    /** Service identity is enforced by OAuthIdentityService; no Taska session is minted here. */
    public Mono<ru.taska.api.common.v1.UserContext> claimSignIn(String code, String nonce, String requestId) {
        return Mono.defer(() -> {
            if (!bridge.enabled()) return Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE"));
            return store.claimSignIn(code, nonce).flatMap(grant -> {
                providers.require(grant.identity().provider());
                return accounts.signInSubject(grant.identity(), requestId);
            }).map(user -> ru.taska.util.UserContexts.builder(user).build());
        });
    }
    public Mono<Exchange> exchange(String code, String requestId) {
        return store.claimGrant("EXCHANGE", code).flatMap(grant -> {
            providers.require(grant.identity().provider());
            return accounts.signIn(grant.identity(), null, requestId)
                .flatMap(session -> session.isPresent() ? Mono.just(new Exchange(session.get(), null, null, grant.returnTo()))
                        : store.grant("SIGNUP", grant.identity(), grant.returnTo()).map(token -> new Exchange(null, token, grant.identity(), grant.returnTo())));
        });
    }
    public Mono<AuthResponseDto> signup(String token, String displayName, String requestId) {
        return Mono.defer(() -> {
            String name = DisplayNames.normalize(displayName);
            return store.claimGrant("SIGNUP", token).flatMap(grant -> {
                        providers.require(grant.identity().provider());
                        return accounts.signIn(grant.identity(), name, requestId);
                    })
                    .map(result -> result.orElseThrow(() -> OAuthErrors.failure("PROVIDER_UNAVAILABLE")));
        });
    }
    private String errorRedirect(String target, Throwable error, String requestId) {
        return redirect(target, "error", OAuthErrors.reason(error)) + (requestId == null || requestId.isBlank() ? "" : "&requestId=" + OAuthUrls.encode(requestId));
    }
    private String redirect(String target, String key, String value) {
        String endpoint = target.equals("oauth") ? OAuthUrls.endpoint(bridge.issuer().toString(), "/login/provider/callback")
                : OAuthUrls.endpoint(properties.getUiBaseUrl(), "/auth/callback");
        return endpoint + "?" + key + "=" + OAuthUrls.encode(value);
    }
    private String target(String value, String binding) {
        String target = value == null || value.isEmpty() ? "ui" : value;
        if (!Set.of("ui", "oauth").contains(target) || target.equals("ui") && binding != null && !binding.isEmpty()
                || target.equals("oauth") && !OAuthFlowStore.valid(binding))
            throw new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.INVALID_ARGUMENT,
                    "Invalid OAuth sign-in target or browser binding", java.util.Map.of("reason", "STATE_INVALID"));
        if (target.equals("oauth") && (!bridge.enabled() || bridge.issuer() == null)) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE");
        return target;
    }
    private void provider(String provider) { if (provider == null || !Set.of("google", "github", "apple").contains(provider)) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE"); }
}
