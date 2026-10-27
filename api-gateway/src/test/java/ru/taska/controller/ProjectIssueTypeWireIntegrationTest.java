package ru.taska.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.DeleteProjectIssueTypeResult;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP and TCP gRPC, using the default gRPC metadata limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProjectIssueTypeWireIntegrationTest.DefaultBodyProbe.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectIssueTypeWireIntegrationTest {
  private static final AtomicInteger MOVE_CALLS = new AtomicInteger();
  private static final AtomicInteger LEGACY_CALLS = new AtomicInteger();
  private static final AtomicReference<ChangeProjectIssueTypeRequest> COMMAND = new AtomicReference<>();
  private static final AtomicReference<DeleteProjectIssueTypeResult> REJECTION = new AtomicReference<>();
  private static final Server PROJECT = startProject();
  private static final UUID PROJECT_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @LocalServerPort int port;
  @MockitoBean GrpcAuthServiceClient auth;
  @MockitoBean ContextMapper contexts;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("grpc.client.project-service.host", () -> "127.0.0.1");
    properties.add("grpc.client.project-service.port", PROJECT::getPort);
  }

  @BeforeEach
  void prepare() {
    MOVE_CALLS.set(0);
    LEGACY_CALLS.set(0);
    COMMAND.set(null);
    REJECTION.set(null);
    when(auth.validateAccessToken(any(), any(), any()))
        .thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
            .setUserContext(UserContext.newBuilder().setUserId(ACTOR_ID.toString())).build()));
    when(contexts.mapToGatewayUserContext(any()))
        .thenReturn(GatewayUserContext.builder().userId(ACTOR_ID.toString())
            .status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
  }

  @AfterAll
  static void stop() {
    PROJECT.shutdownNow();
  }

  @Test
  void unmappedStatusesLargerThanGrpcMetadataLimitReachTheHttpErrorBodyIntact() {
    String statuses = IntStream.range(0, 300).mapToObj(i -> "S%063d".formatted(i))
        .collect(Collectors.joining(","));
    assertThat(statuses.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(8192);
    REJECTION.set(DeleteProjectIssueTypeResult.newBuilder()
        .setRejectionStatus("FAILED_PRECONDITION")
        .setRejectionMessage("Status mappings are required")
        .putAllRejectionDetails(Map.of("reason", "ISSUE_STATUSES_UNMAPPED",
            "statusKeys", statuses, "targetStatusKeys", "TODO,DONE")).build());

    delete("{\"moveIssuesTo\":\"TASK\"}")
        .expectStatus().isBadRequest().expectHeader().exists("X-Request-Id")
        .expectBody().jsonPath("$.code").isEqualTo("FAILED_PRECONDITION")
        .jsonPath("$.details.reason").isEqualTo("ISSUE_STATUSES_UNMAPPED")
        .jsonPath("$.details.statusKeys").isEqualTo(statuses)
        .jsonPath("$.details.targetStatusKeys").isEqualTo("TODO,DONE");
    assertThat(MOVE_CALLS.get()).isEqualTo(1);
    assertThat(LEGACY_CALLS.get()).isZero();
    assertThat(COMMAND.get().getActorUserId()).isEqualTo(ACTOR_ID.toString());
  }

  @Test
  void legacyBodylessAndEmptyJsonDeletionUseTheOriginalRpc() {
    http().delete().uri("/api/v1/projects/" + PROJECT_ID + "/issue-types/REQUEST")
        .header(HttpHeaders.AUTHORIZATION, "Bearer member").exchange()
        .expectStatus().isNoContent();
    delete("{}").expectStatus().isNoContent();
    assertThat(LEGACY_CALLS.get()).isEqualTo(2);
    assertThat(MOVE_CALLS.get()).isZero();
  }

  @Test
  void nonemptyBodyWithUnsupportedContentTypeIsRejectedWithoutIgnoringItsCommand() {
    http().method(HttpMethod.DELETE)
        .uri("/api/v1/projects/" + PROJECT_ID + "/issue-types/REQUEST")
        .header(HttpHeaders.AUTHORIZATION, "Bearer member")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .bodyValue("{\"moveIssuesTo\":\"TASK\"}").exchange()
        .expectStatus().isEqualTo(415);
    assertThat(MOVE_CALLS.get()).isZero();
    assertThat(LEGACY_CALLS.get()).isZero();
  }

  @Test
  void fiveThousandMaximumLengthMappingsFitTheDtoSpecificLimit() {
    String body = command(5000, "TASK");
    assertThat(body.getBytes(StandardCharsets.UTF_8).length).isBetween(650000, 1024 * 1024);
    delete(body).expectStatus().isNoContent();
    assertThat(MOVE_CALLS.get()).isEqualTo(1);
    assertThat(LEGACY_CALLS.get()).isZero();
    assertThat(COMMAND.get().getStatusMappingsCount()).isEqualTo(5000);
    assertThat(COMMAND.get().getStatusMappingsMap()).containsEntry("S%063d".formatted(4999), "T".repeat(64));
  }

  @Test
  void bodyBeyondOneMiBIs413WithoutCallingTheBackend() {
    String body = command(9000, "TASK");
    assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1024 * 1024);
    delete(body).expectStatus().isEqualTo(413);
    assertThat(MOVE_CALLS.get()).isZero();
    assertThat(LEGACY_CALLS.get()).isZero();
  }

  @Test
  void largeDecodedBodyStillUsesBeanValidationBeforeCallingTheBackend() {
    delete(command(5000, "invalid-type")).expectStatus().isBadRequest();
    assertThat(MOVE_CALLS.get()).isZero();
    assertThat(LEGACY_CALLS.get()).isZero();
  }

  @Test
  void otherRequestDtosKeepTheDefault256KiBLimit() {
    http().post().uri("/test/codec/default").contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("content", "x".repeat(300 * 1024))).exchange()
        .expectStatus().isEqualTo(413);
  }

  private WebTestClient.ResponseSpec delete(String body) {
    return http().method(HttpMethod.DELETE)
        .uri("/api/v1/projects/" + PROJECT_ID + "/issue-types/REQUEST")
        .header(HttpHeaders.AUTHORIZATION, "Bearer member")
        .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
  }

  private WebTestClient http() {
    return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(20)).build();
  }

  private static String command(int count, String target) {
    var mappings = new LinkedHashMap<String, String>();
    for (int i = 0; i < count; i++) mappings.put("S%063d".formatted(i), "T".repeat(64));
    return JSON.writeValueAsString(Map.of("moveIssuesTo", target, "statusMappings", mappings));
  }

  private static Server startProject() {
    try {
      return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
          .addService(new ReactorProjectServiceGrpc.ProjectServiceImplBase() {
            @Override
            public Mono<DeleteProjectIssueTypeResult> deleteProjectIssueTypeWithMigration(
                Mono<ChangeProjectIssueTypeRequest> request) {
              return request.map(value -> {
                MOVE_CALLS.incrementAndGet();
                COMMAND.set(value);
                var failure = REJECTION.get();
                return failure == null
                    ? DeleteProjectIssueTypeResult.newBuilder().setCompleted(true).build() : failure;
              });
            }

            @Override
            public Mono<Empty> deleteProjectIssueType(Mono<ChangeProjectIssueTypeRequest> request) {
              return request.map(value -> {
                LEGACY_CALLS.incrementAndGet();
                COMMAND.set(value);
                return Empty.getDefaultInstance();
              });
            }
          }).build().start();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @RestController
  static class DefaultBodyProbe {
    @PostMapping("/test/codec/default")
    Mono<Void> decode(@Valid @RequestBody Mono<DefaultBody> body) {
      return body.then();
    }
  }

  record DefaultBody(String content) {}
}
