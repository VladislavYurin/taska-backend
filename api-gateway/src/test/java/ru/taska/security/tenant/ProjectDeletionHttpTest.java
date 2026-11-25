package ru.taska.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.DeleteProjectRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ReactorProjectDeletionServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.config.GrpcClientConfig;
import ru.taska.controller.ProjectDeletionController;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.GatewayContext;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.service.ProjectDeletionClient;
import tools.jackson.databind.json.JsonMapper;

/** Настоящий HTTP/controller/client/proto путь; удалённая authority проверяется отдельно в E2E. */
class ProjectDeletionHttpTest {
  final UUID tenant = UUID.randomUUID(),
      project = UUID.randomUUID(),
      actor = UUID.randomUUID(),
      operation = UUID.randomUUID();
  final AtomicInteger writes = new AtomicInteger();
  WebTestClient http;
  Server server;
  ManagedChannel channel;

  String url() {
    return "/api/v1/tenants/" + tenant + "/projects/" + project + "/deletion";
  }

  Map<String, Object> body() {
    return Map.of("confirmed", true, "confirmedProjectKey", "DEL");
  }

  Operation result() {
    return Operation.newBuilder()
        .setId(operation.toString())
        .setTenantId(tenant.toString())
        .setResourceId(project.toString())
        .setType("PROJECT_DELETE")
        .setState(Operation.StateValue.STATE_RUNNING)
        .setCreatedAt("2026-01-01T00:00:00Z")
        .setUpdatedAt("2026-01-01T00:00:00Z")
        .build();
  }

  @BeforeEach
  void setup() throws Exception {
    server =
        ServerBuilder.forPort(0)
            .addService(
                new ReactorProjectDeletionServiceGrpc.ProjectDeletionServiceImplBase() {
                  @Override
                  public Mono<Operation> deleteProject(Mono<DeleteProjectRpcRequest> input) {
                    return input.map(
                        q -> {
                          assertThat(q.getProjectId()).isEqualTo(project.toString());
                          assertThat(q.getConfirmed()).isTrue();
                          assertThat(q.getConfirmedProjectKey()).isEqualTo("DEL");
                          assertThat(q.getMutation().getExpectedVersion()).isEqualTo("0");
                          assertThat(q.getMutation().getIdempotencyKey()).isNotBlank();
                          writes.incrementAndGet();
                          return result();
                        });
                  }
                })
            .build()
            .start();
    channel =
        ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    var config = mock(GrpcClientConfig.class);
    when(config.projectDeletionManagedChannel()).thenReturn(channel);
    var security = mock(RpcContextSecurity.class);
    var request =
        RequestContext.newBuilder()
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setTenantId(tenant.toString())
                    .setActorUserId(actor.toString())
                    .addAllScopes(List.of("read", "write")))
            .build();
    var verified =
        new VerifiedTenantContext(
            actor.toString(),
            tenant.toString(),
            "trace",
            "gatewayRequest",
            Set.of("read", "write"),
            request.toByteArray());
    when(security.delegate(any(), anyString(), anyString(), any())).thenReturn(request);
    when(security.sign(any(), any(), anyString(), any()))
        .thenReturn(Mono.just("owned-http-fixture"));
    var factory = mock(GatewayContextFactory.class);
    when(factory.buildContext(anyString(), any(), any()))
        .thenReturn(
            Mono.just(
                new GatewayContext("trace", "api-gateway", null, ActionChannel.UI, verified)));
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("security", security);
    var client = new ProjectDeletionClient(config, beans.getBeanProvider(RpcContextSecurity.class));
    var errors = new GatewayErrorHandler(JsonMapper.builder().build(), new RestErrorMapper());
    org.springframework.test.util.ReflectionTestUtils.setField(errors, "trustedEnabled", true);
    http =
        WebTestClient.bindToController(
                new ProjectDeletionController(
                    new GatewayRequestExecutor(new RequestIdProvider(), factory), client))
            .webFilter(
                (exchange, chain) ->
                    chain
                        .filter(exchange)
                        .onErrorResume(e -> errors.handleError(exchange, e, "trace")))
            .build();
  }

  @AfterEach
  void close() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void acceptedUsesExactVersionAndBoundedOperationMetadata() {
    http.post()
        .uri(url())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", "\"0\"")
        .bodyValue(body())
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.type")
        .isEqualTo("PROJECT_DELETE")
        .jsonPath("$.resourceId")
        .isEqualTo(project.toString());
    assertThat(writes.get()).isEqualTo(1);
  }

  @Test
  void missingAndMalformedHeadersDoNotDispatch() {
    http.post()
        .uri(url())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .bodyValue(body())
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    for (String version : List.of("0", "W/\"0\"", "\"-1\"", "\"2147483648\""))
      http.post()
          .uri(url())
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .header("If-Match", version)
          .bodyValue(body())
          .exchange()
          .expectStatus()
          .isBadRequest();
    http.post()
        .uri(url())
        .header("Idempotency-Key", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        .header("If-Match", "\"0\"")
        .bodyValue(body())
        .exchange()
        .expectStatus()
        .isBadRequest();
    assertThat(writes.get()).isZero();
  }

  @Test
  void unknownDuplicateAndWrongBodyTypesDoNotDispatch() {
    for (String value :
        List.of(
            "{\"confirmed\":true,\"confirmedProjectKey\":\"DEL\",\"other\":1}",
            "{\"confirmed\":true,\"confirmed\":false,\"confirmedProjectKey\":\"DEL\"}",
            "{\"confirmed\":\"true\",\"confirmedProjectKey\":\"DEL\"}"))
      http.post()
          .uri(url())
          .header("Idempotency-Key", UUID.randomUUID().toString())
          .header("If-Match", "\"0\"")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(value)
          .exchange()
          .expectStatus()
          .isBadRequest();
    assertThat(writes.get()).isZero();
  }
}
