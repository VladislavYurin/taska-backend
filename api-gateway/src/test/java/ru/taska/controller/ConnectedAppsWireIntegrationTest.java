package ru.taska.controller;

import static org.assertj.core.api.Assertions.*;

import com.google.protobuf.Empty;
import io.grpc.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.*;
import ru.taska.api.oauth.v1.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectedAppsWireIntegrationTest {
  static final String OWNER = "00000000-0000-0000-0000-000000000042",
      APP = "00000000-0000-0000-0000-000000000043";
  static final AtomicInteger CALLS = new AtomicInteger();
  static final Server SERVER = start();
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("grpc.client.auth-service.host", () -> "localhost");
    p.add("grpc.client.auth-service.port", SERVER::getPort);
  }

  @AfterAll
  static void stop() {
    SERVER.shutdownNow();
  }

  @Test
  void jwtListAndRevokeUseOwnerSessionAndPreserveOpenScopes() {
    http()
        .get()
        .uri("/api/v1/users/me/connected-apps")
        .headers(h -> h.setBearerAuth("jwt"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.available")
        .isEqualTo(true)
        .jsonPath("$.items[0].appId")
        .isEqualTo(APP)
        .jsonPath("$.items[0].scopes[1]")
        .isEqualTo("future")
        .jsonPath("$.items[0].metadataUrl")
        .isEmpty();
    http()
        .delete()
        .uri("/api/v1/users/me/connected-apps/" + APP)
        .headers(h -> h.setBearerAuth("jwt"))
        .exchange()
        .expectStatus()
        .isNoContent();
  }

  @Test
  void patAndDelegationCannotListOrRevokeAndUnavailableKeepsRequestId() {
    int before = CALLS.get();
    for (String token : new String[] {"pat", "delegation"}) {
      http()
          .get()
          .uri("/api/v1/users/me/connected-apps")
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.details.reason")
          .isEqualTo("SESSION_REQUIRED");
      http()
          .delete()
          .uri("/api/v1/users/me/connected-apps/" + APP)
          .headers(h -> h.setBearerAuth(token))
          .exchange()
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.details.reason")
          .isEqualTo("SESSION_REQUIRED");
    }
    assertThat(CALLS.get()).isEqualTo(before);
    http()
        .get()
        .uri("/api/v1/users/me/connected-apps")
        .header("X-Request-Id", "apps-unavailable")
        .headers(h -> h.setBearerAuth("outage"))
        .exchange()
        .expectStatus()
        .isEqualTo(503)
        .expectHeader()
        .valueEquals("X-Request-Id", "apps-unavailable")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAVAILABLE");
  }

  WebTestClient http() {
    return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  static Server start() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorAuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public Mono<ValidateAccessTokenResponse> validateAccessToken(
                    Mono<ValidateAccessTokenRequest> input) {
                  return input.map(
                      req ->
                          ValidateAccessTokenResponse.newBuilder()
                              .setUserContext(
                                  UserContext.newBuilder()
                                      .setUserId(OWNER)
                                      .setTokenId(OWNER)
                                      .setStatus(UserStatus.USER_STATUS_ACTIVE)
                                      .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)
                                      .addScopes("read")
                                      .setTokenKind(
                                          req.getBody().getAccessToken().equals("pat")
                                              ? AccessTokenKind.ACCESS_TOKEN_KIND_PAT
                                              : req.getBody().getAccessToken().equals("delegation")
                                                  ? AccessTokenKind.ACCESS_TOKEN_KIND_DELEGATION
                                                  : AccessTokenKind.ACCESS_TOKEN_KIND_JWT))
                              .build());
                }
              })
          .addService(
              new ReactorConnectedAppsServiceGrpc.ConnectedAppsServiceImplBase() {
                @Override
                public Mono<ListConnectedAppsResponse> listConnectedApps(
                    Mono<ListConnectedAppsRequest> input) {
                  return input.flatMap(
                      req -> {
                        CALLS.incrementAndGet();
                        assertThat(req.getUserId()).isEqualTo(OWNER);
                        if (req.getSessionAccessToken().equals("outage"))
                          return Mono.error(Status.UNAVAILABLE.asRuntimeException());
                        assertThat(req.getSessionAccessToken()).isEqualTo("jwt");
                        var time =
                            com.google.protobuf.Timestamp.newBuilder()
                                .setSeconds(1700000000)
                                .build();
                        return Mono.just(
                            ListConnectedAppsResponse.newBuilder()
                                .setAvailable(true)
                                .addItems(
                                    ConnectedApp.newBuilder()
                                        .setAppId(APP)
                                        .setName("Fixture")
                                        .setHost("client.example.test")
                                        .addScopes("read")
                                        .addScopes("future")
                                        .setConnectedAt(time)
                                        .setLastUsedAt(time)
                                        .setExpiresAt(time))
                                .build());
                      });
                }

                @Override
                public Mono<Empty> revokeConnectedApp(Mono<RevokeConnectedAppRequest> input) {
                  return input.map(
                      req -> {
                        CALLS.incrementAndGet();
                        assertThat(req.getUserId()).isEqualTo(OWNER);
                        assertThat(req.getSessionAccessToken()).isEqualTo("jwt");
                        assertThat(req.getAppId()).isEqualTo(APP);
                        return Empty.getDefaultInstance();
                      });
                }
              })
          .build()
          .start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
