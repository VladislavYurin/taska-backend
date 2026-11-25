package ru.taska.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.PooledDataBuffer;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.GatewayWebExceptionHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.service.TenantRegistryClient;

/** HTTP-entry regressions for raw PATCH parsing and tenant version preconditions. */
@WebFluxTest(controllers = TenantController.class)
@TestPropertySource(properties = "taska.rpc.context.enabled=true")
@Import({GatewayErrorHandler.class, GatewayWebExceptionHandler.class, RestErrorMapper.class})
class TenantControllerHttpRegressionTest {
  private static final UUID TENANT_ID = UUID.fromString("d221b01d-9c5b-4c3b-b3be-b5502f9d1a12");
  private static final String IDEMPOTENCY_KEY = "9a3dd6aa-3f8d-4a31-9abe-7ff25bf93d24";
  private static final String REQUEST_ID = "b5d08772-f839-4f0d-a4ec-5972f10d008a";

  @Autowired private WebTestClient http;
  @Autowired private TenantController controller;
  @MockitoBean private GatewayRequestExecutor executor;
  @MockitoBean private TenantRegistryClient tenantClient;
  @MockitoBean private RequestIdProvider requestIdProvider;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void executeControllerActionWithTestContext() {
    when(requestIdProvider.resolve(any())).thenReturn(REQUEST_ID);
    doAnswer(
            invocation -> {
              Function<GatewayContext, Mono<Object>> action =
                  (Function<GatewayContext, Mono<Object>>) invocation.getArgument(2);
              return action.apply(new GatewayContext(REQUEST_ID, "api-gateway", null));
            })
        .when(executor)
        .execute(any(ServerWebExchange.class), eq(EndpointSecurity.PROTECTED), any());
  }

  @Test
  void patchReadsRawJsonOnceAndPreservesUnicodeName() {
    String name = "Tenant 🚀 обновлён";
    when(tenantClient.renameTenant(eq(TENANT_ID), eq(name), eq(IDEMPOTENCY_KEY), eq(7L), any()))
        .thenReturn(Mono.just(new ru.taska.domain.dto.TenantDto().id(TENANT_ID).name(name)));

    patch("{\"name\":\"Tenant 🚀 обновлён\"}", "\"7\"")
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*")
        .expectBody()
        .jsonPath("$.name")
        .isEqualTo(name)
        .jsonPath("$.creationDeadline")
        .doesNotHaveJsonPath();

    verify(tenantClient).renameTenant(eq(TENANT_ID), eq(name), eq(IDEMPOTENCY_KEY), eq(7L), any());
  }

  @Test
  void patchRejectsUnknownFields() {
    patch("{\"name\":\"valid\",\"role\":\"OWNER\"}", "\"7\"")
        .expectStatus()
        .isBadRequest()
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*")
        .expectBody()
        .jsonPath("$.details.reason")
        .isEqualTo("UNKNOWN_FIELD");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void patchRejectsDuplicateNameKeys() {
    patch("{\"name\":\"first\",\"name\":\"second\"}", "\"7\"")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("name");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void patchRejectsTrailingJsonValue() {
    patch("{\"name\":\"first\"} {\"name\":\"second\"}", "\"7\"")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.details.field")
        .isEqualTo("name");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void missingIfMatchReturns428VersionConflict() {
    http.patch()
        .uri("/api/v1/tenants/{tenantId}", TENANT_ID)
        .header("Idempotency-Key", IDEMPOTENCY_KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"valid\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectHeader()
        .valueMatches("Cache-Control", ".*no-store.*")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void malformedIfMatchReturns400ValidationFailed() {
    patch("{\"name\":\"valid\"}", "7")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void staleIfMatchReturns412VersionConflict() {
    when(tenantClient.renameTenant(eq(TENANT_ID), eq("valid"), eq(IDEMPOTENCY_KEY), eq(7L), any()))
        .thenReturn(
            Mono.error(
                Status.ABORTED
                    .withDescription("Tenant version is stale")
                    .asRuntimeException(
                        GrpcErrorDetails.encode(Map.of("code", "VERSION_CONFLICT")))));

    patch("{\"name\":\"valid\"}", "\"7\"")
        .expectStatus()
        .isEqualTo(412)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");

    verify(tenantClient).renameTenant(eq(TENANT_ID), eq("valid"), eq(IDEMPOTENCY_KEY), eq(7L), any());
  }

  @Test
  void oversizedPatchReturns413WithPrivateNoStore() {
    patch("{\"name\":\"" + "x".repeat(16 * 1024) + "\"}", "\"7\"")
        .expectStatus()
        .isEqualTo(413)
        .expectHeader()
        .valueEquals("Cache-Control", "private, no-store");

    verifyNoInteractions(tenantClient);
  }

  @Test
  void patchPropagatesBufferReadFailureAndReleasesBuffer() {
    var buffer = mock(PooledDataBuffer.class);
    var factory = mock(DataBufferFactory.class);
    var failure = new IllegalStateException("Request body buffer is unavailable");
    when(buffer.factory()).thenReturn(factory);
    when(buffer.readableByteCount()).thenReturn(1);
    when(buffer.isAllocated()).thenReturn(true);
    when(buffer.read(any(byte[].class))).thenThrow(failure);
    when(factory.join(anyList())).thenReturn(buffer);

    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.patch("/api/v1/tenants/{tenantId}", TENANT_ID)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("If-Match", "\"7\"")
                .body(Flux.just(buffer)));

    var actual =
        assertThrows(
            IllegalStateException.class,
            () -> controller.renameTenant(TENANT_ID, IDEMPOTENCY_KEY, "\"7\"", Mono.empty(), exchange)
                .block());
    assertSame(failure, actual);
    verify(buffer).release();
    verifyNoInteractions(tenantClient);
  }

  private WebTestClient.ResponseSpec patch(String jsonBody, String ifMatch) {
    return http.patch()
        .uri("/api/v1/tenants/{tenantId}", TENANT_ID)
        .header("Idempotency-Key", IDEMPOTENCY_KEY)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(jsonBody)
        .exchange();
  }
}
