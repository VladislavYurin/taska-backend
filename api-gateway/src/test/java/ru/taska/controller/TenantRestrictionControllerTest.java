package ru.taska.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.*;
import ru.taska.domain.dto.TenantDto;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.service.TenantRegistryClient;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

@WebFluxTest(controllers = TenantRestrictionController.class)
@Import({
  GatewayRequestExecutor.class,
  GatewayContextFactory.class,
  RequestIdProvider.class,
  BearerTokenExtractor.class,
  GatewayErrorHandler.class,
  RestErrorMapper.class
})
class TenantRestrictionControllerTest {
  @Autowired WebTestClient http;
  @Autowired GatewayErrorHandler errors;
  @MockitoBean TenantRegistryClient client;
  @MockitoBean GrpcAuthServiceClient auth;
  @MockitoBean ContextMapper contexts;
  final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
  final String key = UUID.randomUUID().toString();

  String path() {
    return "/api/v1/tenants/" + tenant + "/pause";
  }

  @BeforeEach
  void setup() {
    org.springframework.test.util.ReflectionTestUtils.setField(errors, "trustedEnabled", true);
    when(auth.validateAccessToken(any(), any(), any()))
        .thenReturn(
            Mono.just(
                ValidateAccessTokenResponse.newBuilder()
                    .setUserContext(UserContext.newBuilder().setUserId(actor.toString()))
                    .build()));
    when(contexts.mapToGatewayUserContext(any()))
        .thenReturn(
            GatewayUserContext.builder()
                .userId(actor.toString())
                .email("fixture@harness.invalid")
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build());
  }

  @AfterEach
  void resetErrorProfile() {
    org.springframework.test.util.ReflectionTestUtils.setField(errors, "trustedEnabled", false);
  }

  @Test
  void anonymousCannotPause() {
    http.put()
        .uri(path())
        .bodyValue(Map.of("confirmed", true))
        .exchange()
        .expectStatus()
        .isUnauthorized();
    verifyNoInteractions(client);
  }

  @Test
  void pauseAndResumeForwardVersionKeyAndConfirmation() {
    when(client.pauseTenant(eq(tenant), eq(key), eq(7L), eq(true), any()))
        .thenReturn(Mono.just(new TenantDto().id(tenant).version("8")));
    http.put()
        .uri(path())
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("If-Match", "\"7\"")
        .header("Idempotency-Key", key)
        .bodyValue(Map.of("confirmed", true))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "private, no-store")
        .expectBody()
        .jsonPath("$.version")
        .isEqualTo("8");
    when(client.resumeTenant(eq(tenant), eq(key), eq(8L), any()))
        .thenReturn(Mono.just(new TenantDto().id(tenant).version("9")));
    http.delete()
        .uri(path())
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("If-Match", "\"8\"")
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isOk();
    verify(client).pauseTenant(eq(tenant), eq(key), eq(7L), eq(true), any());
    verify(client).resumeTenant(eq(tenant), eq(key), eq(8L), any());
  }

  @Test
  void platformBlockForwardsOnlyTheExplicitPayload() {
    when(client.setPlatformBlock(
            eq(tenant), eq(key), eq(7L), eq(true), eq("private audit reason"), eq(true), any()))
        .thenReturn(Mono.just(new TenantDto().id(tenant).version("8")));
    http.put()
        .uri("/api/v1/admin/tenants/" + tenant + "/platform-block")
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("If-Match", "\"7\"")
        .header("Idempotency-Key", key)
        .bodyValue(Map.of("enabled", true, "reason", "private audit reason", "confirmed", true))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.reason")
        .doesNotExist();
    verify(client)
        .setPlatformBlock(
            eq(tenant), eq(key), eq(7L), eq(true), eq("private audit reason"), eq(true), any());
  }

  @Test
  void missingMalformedAndDuplicatePreconditionsNeverReachRpc() {
    http.delete()
        .uri(path())
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    for (String version : List.of("7", "\"0\"", "W/\"7\"", "\"9223372036854775808\""))
      http.delete()
          .uri(path())
          .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
          .header("If-Match", version)
          .header("Idempotency-Key", key)
          .exchange()
          .expectStatus()
          .isBadRequest();
    http.delete()
        .uri(path())
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("If-Match", "\"7\"", "\"8\"")
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isBadRequest();
    verifyNoInteractions(client);
  }

  @Test
  void backendStaleVersionKeeps412AndRequestId() {
    when(client.resumeTenant(eq(tenant), eq(key), eq(7L), any()))
        .thenReturn(
            Mono.error(
                Status.ABORTED.asRuntimeException(
                    mapper.GrpcErrorDetails.encode(Map.of("code", "VERSION_CONFLICT")))));
    http.delete()
        .uri(path())
        .header(HttpHeaders.AUTHORIZATION, "Bearer fixture")
        .header("If-Match", "\"7\"")
        .header("Idempotency-Key", key)
        .exchange()
        .expectStatus()
        .isEqualTo(412)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT")
        .jsonPath("$.requestId")
        .isNotEmpty();
  }
}
