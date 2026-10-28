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
    public record Start(String authorizationUrl, String state, String browserToken) { }
    public record Exchange(AuthResponseDto session, String signupToken, OAuthIdentity identity, String returnTo) { }
    public Mono<Start> start(String provider, String returnTo) {
        return Mono.defer(() -> {
            provider(provider);
            String state = OAuthFlowStore.random(), browser = OAuthFlowStore.random(), nonce = OAuthFlowStore.random(), verifier = OAuthFlowStore.random();
            String url = client.authorizationUrl(provider, state, nonce, verifier);
            return store.saveState(state, browser, new OAuthFlowStore.State(provider, nonce, verifier, OAuthUrls.returnTo(returnTo)))
                    .thenReturn(new Start(url, state, browser));
        });
    }
    public Mono<String> callback(String provider, String state, String browserToken, String code, String error, String appleUser) {
        return callback(provider, state, browserToken, code, error, appleUser, "");
    }
    public Mono<String> callback(String provider, String state, String browserToken, String code, String error, String appleUser, String requestId) {
        return Mono.defer(() -> {
            provider(provider);
            return store.claimState(provider, state, browserToken).flatMap(value -> {
                if (error != null && !error.isEmpty()) return Mono.error(OAuthErrors.failure(error.equals("access_denied") || error.equals("user_cancelled_authorize") ? "PROVIDER_CANCELLED" : "PROVIDER_UNAVAILABLE"));
                return client.authenticate(provider, value, code, appleUser)
                        .flatMap(identity -> accounts.check(identity).then(store.grant("EXCHANGE", identity, value.returnTo())))
                        .map(grant -> redirect("code", grant));
            });
        }).onErrorResume(failure -> Mono.just(redirect("error", OAuthErrors.reason(failure)) + (requestId == null || requestId.isBlank() ? "" : "&requestId=" + OAuthUrls.encode(requestId))));
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
    private String redirect(String key, String value) { return OAuthUrls.endpoint(properties.getUiBaseUrl(), "/auth/callback") + "?" + key + "=" + OAuthUrls.encode(value); }
    private void provider(String provider) { if (provider == null || !Set.of("google", "github", "apple").contains(provider)) throw OAuthErrors.failure("PROVIDER_UNAVAILABLE"); }
}
