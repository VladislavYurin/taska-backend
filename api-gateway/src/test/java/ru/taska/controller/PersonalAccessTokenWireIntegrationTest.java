package ru.taska.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.CreatePersonalAccessTokenRequest;
import ru.taska.api.auth.v1.CreatePersonalAccessTokenResponse;
import ru.taska.api.auth.v1.ListPersonalAccessTokensRequest;
import ru.taska.api.auth.v1.ListPersonalAccessTokensResponse;
import ru.taska.api.auth.v1.PersonalAccessTokenMetadata;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.ReactorPersonalAccessTokenServiceGrpc;
import ru.taska.api.auth.v1.RevokePersonalAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonalAccessTokenWireIntegrationTest {
  private static final String ROOT = "/api/v1/users/me/access-tokens";
  private static final String ID = "00000000-0000-0000-0000-000000000042";
  private static final AtomicInteger CREATED = new AtomicInteger();
  private static final AtomicReference<String> NAME = new AtomicReference<>();
  private static final Server AUTH = startAuth();

  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("grpc.client.auth-service.host", () -> "localhost");
    properties.add("grpc.client.auth-service.port", AUTH::getPort);
  }

  @AfterAll
  static void stop() {
    AUTH.shutdownNow();
  }

  @Test
  void jwtApiUsesRealGrpcAndPatMetadataCannotManageTokens() {
    http()
        .post()
        .uri(ROOT)
        .headers(h -> h.setBearerAuth("jwt-wire"))
        .header("X-Request-Id", "wire-request")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"  Laptop  \",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectBody()
        .jsonPath("$.secret")
        .isEqualTo("only-in-create-response")
        .jsonPath("$.token.id")
        .isEqualTo(ID);
    assertThat(CREATED.get()).isEqualTo(1);
    assertThat(NAME.get()).isEqualTo("Laptop");
    String list =
        http()
            .get()
            .uri(ROOT)
            .headers(h -> h.setBearerAuth("jwt-wire"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(list).contains("maxLifetimeDays", "tsk_pat_abcd").doesNotContain("secret", "hash");
    for (int i = 0; i < 2; i++)
      http()
          .delete()
          .uri(ROOT + "/" + ID)
          .headers(h -> h.setBearerAuth("jwt-wire"))
          .exchange()
          .expectStatus()
          .isNoContent();
    http()
        .get()
        .uri(ROOT)
        .headers(h -> h.setBearerAuth("pat-read"))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("SESSION_REQUIRED");
  }

  @Test
  void httpReadPatReadsAndCannotPatchCreateOrReachAdminReadOnly() {
    http()
        .get()
        .uri("/api/gateway/me")
        .headers(h -> h.setBearerAuth("pat-read"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.userContext.tokenKind")
        .isEqualTo("PAT");
    http()
        .post()
        .uri("/api/v1/projects")
        .headers(h -> h.setBearerAuth("pat-read"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"projectKey\":\"TEST\",\"name\":\"Test\"}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PERMISSION_DENIED")
        .jsonPath("$.details.requiredScope")
        .isEqualTo("write");
    http()
        .patch()
        .uri("/api/v1/projects/" + ID)
        .headers(h -> h.setBearerAuth("pat-read"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Title\"}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("INSUFFICIENT_SCOPE");
    http()
        .get()
        .uri("/api/v1/readonly/catalog")
        .headers(h -> h.setBearerAuth("pat-read"))
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("SESSION_REQUIRED");
    http()
        .post()
        .uri("/api/v1/admin/users/" + ID + "/block")
        .headers(h -> h.setBearerAuth("pat-read"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Test\"}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("SESSION_REQUIRED");
    http()
        .get()
        .uri("/api/gateway/me")
        .headers(h -> h.setBearerAuth("revoked"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void lockedJwtStillReadsButCannotCreatePat() {
    http()
        .get()
        .uri("/api/gateway/me")
        .headers(h -> h.setBearerAuth("locked-jwt"))
        .exchange()
        .expectStatus()
        .isOk();
    http()
        .post()
        .uri(ROOT)
        .headers(h -> h.setBearerAuth("locked-jwt"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Laptop\",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  private WebTestClient http() {
    return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static PersonalAccessTokenMetadata metadata() {
    return PersonalAccessTokenMetadata.newBuilder()
        .setId(ID)
        .setName("Laptop")
        .setTokenPrefix("tsk_pat_abcd")
        .addScopes("read")
        .setCreatedAt(Timestamp.newBuilder().setSeconds(1_792_900_000))
        .setExpiresAt(Timestamp.newBuilder().setSeconds(1_795_900_000))
        .build();
  }

  private static Server startAuth() {
    try {
      return ServerBuilder.forPort(0)
          .addService(
              new ReactorAuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public Mono<ValidateAccessTokenResponse> validateAccessToken(
                    Mono<ValidateAccessTokenRequest> request) {
                  return request.flatMap(
                      value -> {
                        assertThat(value.getBody().getAllowPersonalToken()).isTrue();
                        String token = value.getBody().getAccessToken();
                        if (token.equals("revoked"))
                          return Mono.error(Status.UNAUTHENTICATED.asRuntimeException());
                        return Mono.just(
                            ValidateAccessTokenResponse.newBuilder()
                                .setUserContext(
                                    UserContext.newBuilder()
                                        .setUserId(ID)
                                        .setStatus(
                                            token.equals("locked-jwt")
                                                ? UserStatus.USER_STATUS_LOCKED
                                                : UserStatus.USER_STATUS_ACTIVE)
                                        .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                                        .setTokenId(ID)
                                        .addScopes("read")
                                        .setTokenKind(
                                            token.equals("pat-read")
                                                ? AccessTokenKind.ACCESS_TOKEN_KIND_PAT
                                                : AccessTokenKind.ACCESS_TOKEN_KIND_JWT))
                                .build());
                      });
                }
              })
          .addService(
              new ReactorPersonalAccessTokenServiceGrpc.PersonalAccessTokenServiceImplBase() {
                @Override
                public Mono<CreatePersonalAccessTokenResponse> createPersonalAccessToken(
                    Mono<CreatePersonalAccessTokenRequest> request) {
                  return request.map(
                      value -> {
                        assertThat(value.getSessionAccessToken()).isEqualTo("jwt-wire");
                        assertThat(value.getHeader().getRequestId()).isEqualTo("wire-request");
                        assertThat(value.getScopesList()).containsExactly("read");
                        CREATED.incrementAndGet();
                        NAME.set(value.getName());
                        return CreatePersonalAccessTokenResponse.newBuilder()
                            .setToken(metadata())
                            .setSecret("only-in-create-response")
                            .build();
                      });
                }

                @Override
                public Mono<ListPersonalAccessTokensResponse> listPersonalAccessTokens(
                    Mono<ListPersonalAccessTokensRequest> request) {
                  return request.map(
                      value -> {
                        assertThat(value.getSessionAccessToken()).isEqualTo("jwt-wire");
                        return ListPersonalAccessTokensResponse.newBuilder()
                            .addItems(metadata())
                            .setMaxActiveTokens(10)
                            .setMaxLifetimeDays(90)
                            .build();
                      });
                }

                @Override
                public Mono<Empty> revokePersonalAccessToken(
                    Mono<RevokePersonalAccessTokenRequest> request) {
                  return request.map(
                      value -> {
                        assertThat(value.getSessionAccessToken()).isEqualTo("jwt-wire");
                        assertThat(value.getTokenId()).isEqualTo(ID);
                        return Empty.getDefaultInstance();
                      });
                }
              })
          .build()
          .start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }
}
