package ru.taska.controller;

import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import ru.taska.api.oauth.v1.StartOAuthResponse;
import ru.taska.domain.dto.*;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcOAuthClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@WebFluxTest(OAuthController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class, BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class, ru.taska.security.SignupClientAddress.class})
class OAuthControllerTest {
    @Autowired WebTestClient http;
    @Autowired GatewayContextFactory contexts;
    @MockitoBean GrpcOAuthClient oauth;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper mapper;
    private static final String STATE = "s".repeat(43), BROWSER = "b".repeat(43);
    private static final String COOKIE = "__Host-taska_oauth_" + STATE;
    @BeforeEach void prepare() { ReflectionTestUtils.setField(contexts, "nodeId", "test-node"); }

    @Test void startIsPublicAndBrowserCookieHasAllCrossSiteCsrfAttributes() {
        when(oauth.start(eq("google"), eq("/projects"), eq("unknown"), any())).thenReturn(Mono.just(StartOAuthResponse.newBuilder()
                .setAuthorizationUrl("https://accounts.example/authorize?state=" + STATE).setState(STATE).setBrowserToken(BROWSER).build()));
        http.get().uri("/api/v1/auth/oauth/google/start").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "https://accounts.example/authorize?state=" + STATE)
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie).contains(COOKIE + "=" + BROWSER, "HttpOnly", "Secure", "SameSite=None", "Path=/", "Max-Age=600"))
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody().isEmpty();
        verifyNoInteractions(auth);
    }
    @Test void googleCallbackPassesBindingCookieAndOnlyRedirectsToOpaqueCode() {
        when(oauth.callback(eq("google"), eq(STATE), eq(BROWSER), eq("provider-code"), isNull(), isNull(), eq("unknown"), any()))
                .thenReturn(Mono.just("https://ui.example/auth/callback?code=one-time"));
        http.get().uri("/api/v1/auth/oauth/google/callback?state=" + STATE + "&code=provider-code").cookie(COOKIE, BROWSER)
                .exchange().expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "https://ui.example/auth/callback?code=one-time")
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> assertThat(cookie).contains("Max-Age=0", "SameSite=None", "Secure", "HttpOnly"))
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer").expectBody().isEmpty();
        verifyNoInteractions(auth);
    }
    @Test void appleFormPostCarriesFirstLoginUserAndBrowserCookie() {
        String user = "{\"name\":{\"firstName\":\"Anna\"}}";
        when(oauth.callback(eq("apple"), eq(STATE), eq(BROWSER), eq("apple-code"), isNull(), eq(user), eq("unknown"), any()))
                .thenReturn(Mono.just("https://ui.example/auth/callback?code=apple-one-time"));
        http.post().uri("/api/v1/auth/oauth/apple/callback").cookie(COOKIE, BROWSER)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("state", STATE).with("code", "apple-code").with("user", user))
                .exchange().expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION, "https://ui.example/auth/callback?code=apple-one-time");
        verify(oauth).callback(eq("apple"), eq(STATE), eq(BROWSER), eq("apple-code"), isNull(), eq(user), eq("unknown"), any());
    }
    @Test void exchangeAndSignupArePublicJsonAndNeverCacheSessions() {
        var signup = new OAuthSignupStateDto().signupToken("signup-one-time").provider(OAuthSignupStateDto.ProviderEnum.GITHUB)
                .email("person@example.test").suggestedDisplayName(null);
        when(oauth.exchange(any(), eq("unknown"), any())).thenReturn(Mono.just(new OAuthExchangeResponseDto().status(OAuthExchangeResponseDto.StatusEnum.SIGNUP_REQUIRED).signup(signup).returnTo("/projects")));
        http.post().uri("/api/v1/auth/oauth/exchange").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"code\":\"one-time\"}")
                .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody()
                .jsonPath("$.status").isEqualTo("SIGNUP_REQUIRED").jsonPath("$.signup.provider").isEqualTo("GITHUB")
                .jsonPath("$.returnTo").isEqualTo("/projects").jsonPath("$.session").isEmpty();
        when(oauth.signup(any(), eq("unknown"), any())).thenReturn(Mono.just(new LoginResponseDto().accessToken("access").refreshToken("refresh").expiresIn(900L)));
        http.post().uri("/api/v1/auth/oauth/signup").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"signupToken\":\"signup-one-time\",\"displayName\":\"Chosen Name\"}")
                .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody()
                .jsonPath("$.accessToken").isEqualTo("access").jsonPath("$.expiresIn").isEqualTo(900);
        verifyNoInteractions(auth);
    }
    @Test void startFailuresRedirectToConfiguredUiOriginWithEncodedCorrelationAndStableReason() {
        when(oauth.start(eq("google"), any(), any(), any())).thenReturn(Mono.error(io.grpc.Status.UNAVAILABLE
                .withDescription("private provider secret").asRuntimeException()));
        http.get().uri("/api/v1/auth/oauth/google/start").header("X-Request-Id", "trace&private=x")
                .exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "https://taska.ozero.dev/auth/callback?error=PROVIDER_UNAVAILABLE&requestId=trace%26private%3Dx")
                .expectHeader().valueEquals("X-Request-Id", "trace&private=x")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody().isEmpty();
    }
    @Test void unavailableBackendWithoutIncomingRequestIdGetsGeneratedCorrelation() {
        when(oauth.start(eq("google"), any(), any(), any())).thenReturn(Mono.error(io.grpc.Status.UNAVAILABLE.asRuntimeException()));
        var result = http.get().uri("/api/v1/auth/oauth/google/start").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody().isEmpty();
        String id = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(id).isNotBlank();
        assertThat(java.util.UUID.fromString(id)).isNotNull();
        assertThat(result.getResponseHeaders().getLocation().toString())
                .isEqualTo("https://taska.ozero.dev/auth/callback?error=PROVIDER_UNAVAILABLE&requestId=" + id);
    }

    @Test void grpcDomainFailureKeepsKnownReasonWithoutLeakingUnexpectedReason() {
        for (String reason : java.util.List.of("SIGNUP_DISABLED", "private-secret")) {
            when(oauth.start(eq("google"), any(), any(), any())).thenReturn(Mono.error(io.grpc.Status.PERMISSION_DENIED
                    .asRuntimeException(GrpcErrorDetails.encode(java.util.Map.of("reason", reason)))));
            http.get().uri("/api/v1/auth/oauth/google/start").header("X-Request-Id", "known-reason")
                    .exchange().expectStatus().isFound().expectHeader().valueEquals(HttpHeaders.LOCATION,
                            "https://taska.ozero.dev/auth/callback?error=" + (reason.equals("SIGNUP_DISABLED") ? reason : "PROVIDER_UNAVAILABLE") + "&requestId=known-reason");
        }
    }

    @Test void missingBindingAndBackendFailureDoNotExposeProviderDetails() {
        when(oauth.callback(eq("github"), eq(STATE), eq(""), any(), any(), any(), eq("unknown"), any()))
                .thenReturn(Mono.error(new IllegalStateException("private provider details")));
        http.get().uri("/api/v1/auth/oauth/github/callback?state=" + STATE + "&code=x").header("X-Request-Id", "failure-test").exchange().expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "https://taska.ozero.dev/auth/callback?error=PROVIDER_UNAVAILABLE&requestId=failure-test").expectBody().isEmpty();
    }
}
