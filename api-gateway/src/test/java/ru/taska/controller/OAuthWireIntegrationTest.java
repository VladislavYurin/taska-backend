package ru.taska.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.oauth.v1.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "signup.trusted-proxy-cidrs=127.0.0.0/8")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OAuthWireIntegrationTest {
  static final AtomicBoolean LIMITED = new AtomicBoolean();
  static final AtomicReference<String> REASON = new AtomicReference<>("STATE_INVALID");
  static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  static final String IP = "203.0.113.7", STATE = "s".repeat(43);
  static final Server AUTH = startAuth();
  @LocalServerPort int port;

  record Call(String route, String ip, String state, String name) {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("grpc.client.auth-service.host", () -> "127.0.0.1");
    properties.add("grpc.client.auth-service.port", AUTH::getPort);
  }

  @BeforeEach
  void reset() {
    LIMITED.set(false);
    REASON.set("STATE_INVALID");
    CALLS.clear();
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
  }

  @Test
  void allPublicRoutesForwardOnlyTheResolvedProxyAddressIntoRealGrpcFields() {
    request("start").expectStatus().isFound();
    request("callback").expectStatus().isFound();
    request("exchange").expectStatus().isOk();
    request("signup").expectStatus().isOk();
    applePost().expectStatus().isFound();
    assertThat(CALLS)
        .extracting(Call::route)
        .containsExactly("start", "callback", "exchange", "signup", "callback");
    assertThat(CALLS).allSatisfy(call -> assertThat(call.ip()).isEqualTo(IP));
    assertThat(CALLS.get(1).state()).isEqualTo(STATE);
    assertThat(CALLS.get(3).name()).isEqualTo("  Chosen Name  ");
  }

  @Test
  void eachRouteIncludingAppleCallbackPreservesGrpc429RetryAfterAndNoStore() {
    LIMITED.set(true);
    for (String route : List.of("start", "callback", "exchange", "signup")) quota(request(route));
    quota(applePost());
    assertThat(CALLS).hasSize(5).allSatisfy(call -> assertThat(call.ip()).isEqualTo(IP));
  }

  @Test
  void gatewayForwardsEveryDomainErrorRedirectWithoutCollapsingItToProviderUnavailable() {
    for (String reason :
        List.of(
            "PROVIDER_CANCELLED",
            "STATE_INVALID",
            "EMAIL_NOT_VERIFIED",
            "ACCOUNT_BLOCKED",
            "ACCOUNT_LOCKED",
            "SIGNUP_DISABLED",
            "PROVIDER_UNAVAILABLE")) {
      REASON.set(reason);
      request("callback")
          .expectStatus()
          .isFound()
          .expectHeader()
          .valueEquals(
              HttpHeaders.LOCATION, "https://taska.ozero.dev/auth/callback?error=" + reason)
          .expectHeader()
          .valueEquals("Referrer-Policy", "no-referrer")
          .expectBody()
          .isEmpty();
    }
  }

  @Test
  void wrongCallbackMethodsStillReachQuotaWithoutClaimingTheBrowserState() {
    http()
        .get()
        .uri("/api/v1/auth/oauth/apple/callback?state=" + STATE + "&code=secret")
        .header("X-Forwarded-For", IP)
        .exchange()
        .expectStatus()
        .isFound();
    http()
        .post()
        .uri("/api/v1/auth/oauth/google/callback")
        .header("X-Forwarded-For", IP)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData("state", STATE).with("code", "secret"))
        .exchange()
        .expectStatus()
        .isFound();
    assertThat(CALLS)
        .hasSize(2)
        .allSatisfy(
            call -> {
              assertThat(call.ip()).isEqualTo(IP);
              assertThat(call.state()).isEmpty();
            });
    LIMITED.set(true);
    quota(
        http()
            .get()
            .uri("/api/v1/auth/oauth/apple/callback?state=" + STATE)
            .header("X-Forwarded-For", IP)
            .exchange());
  }

  @Test
  void invalidDisplayNameIsSentToAuthSoFailedSignupStillConsumesItsIpReservation() {
    http()
        .post()
        .uri("/api/v1/auth/oauth/signup")
        .header("X-Forwarded-For", IP)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("signupToken", "unused", "displayName", "bad\nname"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("DISPLAY_NAME_INVALID");
    assertThat(CALLS).hasSize(1);
    assertThat(CALLS.getFirst()).isEqualTo(new Call("signup", IP, "", "bad\nname"));
  }

  WebTestClient.ResponseSpec request(String route) {
    if (route.equals("start"))
      return http()
          .get()
          .uri("/api/v1/auth/oauth/google/start")
          .header("X-Forwarded-For", IP)
          .exchange();
    if (route.equals("callback"))
      return http()
          .get()
          .uri("/api/v1/auth/oauth/google/callback?state=" + STATE)
          .header("X-Forwarded-For", IP)
          .exchange();
    return http()
        .post()
        .uri("/api/v1/auth/oauth/" + route)
        .header("X-Forwarded-For", IP)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            route.equals("exchange")
                ? Map.of("code", "one-time")
                : Map.of("signupToken", "one-time", "displayName", "  Chosen Name  "))
        .exchange();
  }

  WebTestClient.ResponseSpec applePost() {
    return http()
        .post()
        .uri("/api/v1/auth/oauth/apple/callback")
        .header("X-Forwarded-For", IP)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(BodyInserters.fromFormData("state", STATE).with("code", "apple-code"))
        .exchange();
  }

  void quota(WebTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals(HttpHeaders.RETRY_AFTER, "120")
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectHeader()
        .exists("X-Request-Id")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RESOURCE_EXHAUSTED")
        .jsonPath("$.details.retryAfterSeconds")
        .isEqualTo("120");
  }

  WebTestClient http() {
    return WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(10))
        .build();
  }

  static <T> Mono<T> respond(Call call, T value) {
    CALLS.add(call);
    return LIMITED.get()
        ? Mono.error(
            Status.RESOURCE_EXHAUSTED
                .withDescription("Please try again later")
                .asRuntimeException(GrpcErrorDetails.encode(Map.of("retryAfterSeconds", "120"))))
        : Mono.just(value);
  }

  private static Server startAuth() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorOAuthServiceGrpc.OAuthServiceImplBase() {
                @Override
                public Mono<StartOAuthResponse> startOAuth(Mono<StartOAuthRequest> request) {
                  return request.flatMap(
                      value ->
                          respond(
                              new Call("start", value.getClientIp(), "", ""),
                              StartOAuthResponse.newBuilder()
                                  .setAuthorizationUrl("https://provider.example/authorize")
                                  .setState(STATE)
                                  .setBrowserToken("b".repeat(43))
                                  .build()));
                }

                @Override
                public Mono<CompleteOAuthResponse> completeOAuth(
                    Mono<CompleteOAuthRequest> request) {
                  return request.flatMap(
                      value ->
                          respond(
                              new Call("callback", value.getClientIp(), value.getState(), ""),
                              CompleteOAuthResponse.newBuilder()
                                  .setRedirectUrl(
                                      "https://taska.ozero.dev/auth/callback?error=" + REASON.get())
                                  .build()));
                }

                @Override
                public Mono<ExchangeOAuthResponse> exchangeOAuth(
                    Mono<ExchangeOAuthRequest> request) {
                  return request.flatMap(
                      value ->
                          respond(
                              new Call("exchange", value.getClientIp(), "", ""),
                              ExchangeOAuthResponse.newBuilder()
                                  .setStatus("SIGNED_IN")
                                  .setSession(session())
                                  .setReturnTo("/projects")
                                  .build()));
                }

                @Override
                public Mono<LoginResponse> signupOAuth(Mono<SignupOAuthRequest> request) {
                  return request.flatMap(
                      value -> {
                        var call =
                            new Call("signup", value.getClientIp(), "", value.getDisplayName());
                        if (value.getDisplayName().contains("\n")) {
                          CALLS.add(call);
                          return Mono.error(
                              Status.INVALID_ARGUMENT
                                  .withDescription("Invalid name")
                                  .asRuntimeException(
                                      GrpcErrorDetails.encode(
                                          Map.of("reason", "DISPLAY_NAME_INVALID"))));
                        }
                        return respond(call, session());
                      });
                }
              })
          .build()
          .start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  static LoginResponse session() {
    return LoginResponse.newBuilder()
        .setAccessToken("access")
        .setRefreshToken("refresh")
        .setExpiresIn(900)
        .build();
  }
}
