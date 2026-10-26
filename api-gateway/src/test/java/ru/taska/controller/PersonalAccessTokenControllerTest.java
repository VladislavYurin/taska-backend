package ru.taska.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.dto.CreatePersonalAccessTokenRequestDto;
import ru.taska.domain.dto.CreatePersonalAccessTokenResponseDto;
import ru.taska.domain.dto.PersonalAccessTokenDto;
import ru.taska.domain.dto.PersonalAccessTokenScopeDto;
import ru.taska.domain.dto.PersonalAccessTokensResponseDto;
import ru.taska.domain.dto.PersonalTokenPolicyDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.PersonalTokenCreationLimiter;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.AuthMapper;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcPersonalAccessTokenClient;

@WebFluxTest(controllers = PersonalAccessTokenController.class)
@Import({
  GatewayRequestExecutor.class,
  GatewayContextFactory.class,
  BearerTokenExtractor.class,
  RequestIdProvider.class,
  ContextMapper.class,
  AuthMapper.class,
  GatewayErrorHandler.class,
  RestErrorMapper.class
})
class PersonalAccessTokenControllerTest {
  private static final String ROOT = "/api/v1/users/me/access-tokens";
  @Autowired WebTestClient http;
  @MockitoBean GrpcAuthServiceClient auth;
  @MockitoBean GrpcPersonalAccessTokenClient tokens;
  @MockitoBean PersonalTokenCreationLimiter limiter;

  @BeforeEach
  void jwt() {
    authenticated(AccessTokenKind.ACCESS_TOKEN_KIND_JWT, UserStatus.USER_STATUS_ACTIVE);
  }

  @Test
  void createsOnceReturnsMetadataAndSecretWithoutCaching() {
    var metadata =
        new PersonalAccessTokenDto()
            .id(UUID.randomUUID())
            .name("Laptop")
            .tokenPrefix("tsk_pat_abcd")
            .scopes(List.of(PersonalAccessTokenScopeDto.READ));
    when(tokens.create(any(), anyString(), any()))
        .thenReturn(
            Mono.just(
                new CreatePersonalAccessTokenResponseDto()
                    .token(metadata)
                    .secret("only-once-secret")));
    http.post()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-session")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\" Laptop \",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectBody()
        .jsonPath("$.secret")
        .isEqualTo("only-once-secret")
        .jsonPath("$.token.tokenPrefix")
        .isEqualTo("tsk_pat_abcd")
        .jsonPath("$.token.tokenHash")
        .doesNotExist();
    var request = ArgumentCaptor.forClass(CreatePersonalAccessTokenRequestDto.class);
    verify(tokens).create(request.capture(), org.mockito.ArgumentMatchers.eq("jwt-session"), any());
    verify(limiter).acquire("user-id");
  }

  @Test
  void listsPolicyWithoutSecretAndRevokesIdempotently() {
    when(tokens.list(anyString(), any()))
        .thenReturn(
            Mono.just(
                new PersonalAccessTokensResponseDto()
                    .items(List.of())
                    .policy(new PersonalTokenPolicyDto().maxLifetimeDays(90).maxActiveTokens(10))));
    when(tokens.revoke(anyString(), anyString(), any())).thenReturn(Mono.empty());
    http.get()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-session")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectBody()
        .jsonPath("$.policy.maxLifetimeDays")
        .isEqualTo(90)
        .jsonPath("$.secret")
        .doesNotExist();
    for (int i = 0; i < 2; i++)
      http.delete()
          .uri(ROOT + "/" + new UUID(0, 1))
          .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-session")
          .exchange()
          .expectStatus()
          .isNoContent();
    verifyNoInteractions(limiter);
  }

  @Test
  void patCannotListCreateOrRevokeEvenWithWriteAndGlobalAdmin() {
    authenticated(AccessTokenKind.ACCESS_TOKEN_KIND_PAT, UserStatus.USER_STATUS_ACTIVE);
    http.get()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer pat")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("PERMISSION_DENIED")
        .jsonPath("$.details.reason")
        .isEqualTo("SESSION_REQUIRED");
    http.post()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer pat")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Laptop\",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isForbidden();
    http.delete()
        .uri(ROOT + "/" + UUID.randomUUID())
        .header(HttpHeaders.AUTHORIZATION, "Bearer pat")
        .exchange()
        .expectStatus()
        .isForbidden();
    verifyNoInteractions(tokens, limiter);
  }

  @Test
  void lockedJwtCannotCreateAndRevokedPatIs401() {
    authenticated(AccessTokenKind.ACCESS_TOKEN_KIND_JWT, UserStatus.USER_STATUS_LOCKED);
    http.post()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-session")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Laptop\",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    when(auth.validateAccessToken(anyString(), anyString(), anyString()))
        .thenReturn(
            Mono.error(
                Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException()));
    http.get()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer revoked-pat")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    verifyNoInteractions(tokens, limiter);
  }

  @Test
  void rateLimitUsesRetryHeaderAndCommonErrorBodyAndDoesNotCallCreate() {
    org.mockito.Mockito.doThrow(
            Status.RESOURCE_EXHAUSTED
                .withDescription("Rate limited")
                .asRuntimeException(
                    mapper.GrpcErrorDetails.encode(java.util.Map.of("retryAfterSeconds", "120"))))
        .when(limiter)
        .acquire("user-id");
    http.post()
        .uri(ROOT)
        .header(HttpHeaders.AUTHORIZATION, "Bearer jwt-session")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Laptop\",\"scopes\":[\"read\"]}")
        .exchange()
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .valueEquals(HttpHeaders.RETRY_AFTER, "120")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RESOURCE_EXHAUSTED")
        .jsonPath("$.details.retryAfterSeconds")
        .isEqualTo("120");
    verifyNoInteractions(tokens);
  }

  private void authenticated(AccessTokenKind kind, UserStatus status) {
    when(auth.validateAccessToken(anyString(), anyString(), anyString()))
        .thenReturn(
            Mono.just(
                ValidateAccessTokenResponse.newBuilder()
                    .setUserContext(
                        UserContext.newBuilder()
                            .setUserId("user-id")
                            .setStatus(status)
                            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setTokenKind(kind)
                            .setTokenId("token-id")
                            .addAllScopes(List.of("read", "write")))
                    .build()));
  }
}
