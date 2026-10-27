package ru.taska.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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
import ru.taska.api.project.v1.CreateProjectIssueTypeWithWorkflowResult;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowBindingOperationResult;
import ru.taska.api.workflow.v1.WorkflowMutationRequest;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.domain.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import tools.jackson.databind.json.JsonMapper;

/** Public HTTP -> real Netty TCP RPC, retaining the default gRPC metadata limit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WorkflowBindingWireIntegrationTest.DefaultBodyProbe.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowBindingWireIntegrationTest {
    private static final UUID PROJECT_ID = UUID.randomUUID(), WORKFLOW_ID = UUID.randomUUID(), ACTOR = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger CREATES = new AtomicInteger(), PATCHES = new AtomicInteger(), LEGACY = new AtomicInteger(), TYPES = new AtomicInteger();
    private static final AtomicReference<CreateWorkflowRequest> CREATE = new AtomicReference<>();
    private static final AtomicReference<WorkflowMutationRequest> PATCH = new AtomicReference<>();
    private static final AtomicReference<ChangeProjectIssueTypeRequest> TYPE = new AtomicReference<>();
    private static final AtomicReference<WorkflowBindingOperationResult> ANSWER = new AtomicReference<>();
    private static final Server WORKFLOWS = workflows();
    private static final Server PROJECTS = projects();
    @LocalServerPort int port;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("grpc.client.workflow-service.host", () -> "127.0.0.1");
        properties.add("grpc.client.workflow-service.port", WORKFLOWS::getPort);
        properties.add("grpc.client.project-service.host", () -> "127.0.0.1");
        properties.add("grpc.client.project-service.port", PROJECTS::getPort);
    }

    @BeforeEach
    void prepare() {
        CREATES.set(0); PATCHES.set(0); LEGACY.set(0); TYPES.set(0);
        CREATE.set(null); PATCH.set(null); TYPE.set(null); ANSWER.set(null);
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(ACTOR.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(user(List.of("read", "write")));
    }

    @AfterAll
    static void close() { WORKFLOWS.shutdownNow(); PROJECTS.shutdownNow(); }

    @Test
    void createAndBindingPatchKeepThePublicWorkflowEnvelopeAndTrustedCommand() {
        create(body(Map.of("BACKLOG", "TODO"))).expectStatus().isCreated().expectBody()
                .jsonPath("$.workflow.id").isEqualTo(WORKFLOW_ID.toString())
                .jsonPath("$.issueTypes[0]").isEqualTo("TASK");
        assertThat(CREATE.get().getBody().getStatusMappingsMap()).containsEntry("BACKLOG", "TODO");
        assertThat(CREATE.get().getBody().getActorUserId()).isEqualTo(ACTOR.toString());
        assertThat(CREATE.get().getHeader().getRequestId()).isEqualTo("binding-wire-436");
        patch("{\"version\":4,\"issueTypes\":[\"TASK\",\"EPIC\"],\"statusMappings\":{\"BACKLOG\":\"TODO\"}}")
                .expectStatus().isOk().expectBody().jsonPath("$.workflow.id").isEqualTo(WORKFLOW_ID.toString());
        assertThat(PATCH.get().getReplaceIssueTypes()).isTrue();
        assertThat(PATCH.get().getIssueTypeKeysList()).containsExactly("TASK", "EPIC");
        assertThat(PATCH.get().hasName()).isFalse();
        assertThat(PATCH.get().getVersion()).isEqualTo(4);
        assertThat(PATCH.get().getActorUserId()).isEqualTo(ACTOR.toString());
        assertThat(PATCH.get().getHeader().getRequestId()).isEqualTo("binding-wire-436");
        assertThat(LEGACY).hasValue(0);
    }

    @Test
    void oldNameOnlyPatchKeepsLegacyRpcAndCanReturnAnEmptyOwnedDonor() {
        ANSWER.set(WorkflowBindingOperationResult.newBuilder().setResult(workflow(List.of())).build());
        patch("{\"name\":\"Empty donor\"}").expectStatus().isOk().expectBody()
                .jsonPath("$.workflow.id").isEqualTo(WORKFLOW_ID.toString())
                .jsonPath("$.issueTypes").isEmpty().jsonPath("$.shared").isEqualTo(false);
        assertThat(LEGACY).hasValue(1); assertThat(PATCHES).hasValue(0);
        assertThat(PATCH.get().getReplaceIssueTypes()).isFalse();
        assertThat(PATCH.get().hasVersion()).isFalse();
    }

    @Test
    void unmappedDetailsOverEightKiBArriveIntactFromBothNewRpcBodies() {
        String statuses = IntStream.range(0, 300).mapToObj(i -> "S%063d".formatted(i)).collect(Collectors.joining(","));
        assertThat(statuses.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(8192);
        ANSWER.set(WorkflowBindingOperationResult.newBuilder().setRejectionStatus("FAILED_PRECONDITION")
                .setRejectionMessage("Supply status mappings").putAllRejectionDetails(Map.of("reason", "WORKFLOW_ISSUES_UNMAPPED",
                        "issueCount", "300", "statusKeys", statuses, "targetStatusKeys", "TODO,DONE")).build());
        for (var result : List.of(create(body(Map.of())), patch("{\"version\":4,\"issueTypes\":[\"TASK\"]}"))) {
            result.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("FAILED_PRECONDITION")
                    .jsonPath("$.details.reason").isEqualTo("WORKFLOW_ISSUES_UNMAPPED")
                    .jsonPath("$.details.issueCount").isEqualTo("300").jsonPath("$.details.statusKeys").isEqualTo(statuses)
                    .jsonPath("$.details.targetStatusKeys").isEqualTo("TODO,DONE");
        }
    }

    @Test
    void validFiveThousandMappingsExceedDefaultLimitAndReachBothRpcs() {
        Map<String, String> mappings = mappings();
        String createBody = body(mappings);
        String patchBody = JSON.writeValueAsString(Map.of("version", 4, "issueTypes", List.of("TASK"), "statusMappings", mappings));
        assertThat(createBody.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(256 * 1024);
        create(createBody).expectStatus().isCreated(); patch(patchBody).expectStatus().isOk();
        assertThat(CREATE.get().getBody().getStatusMappingsMap()).containsExactlyInAnyOrderEntriesOf(mappings);
        assertThat(PATCH.get().getStatusMappingsMap()).containsExactlyInAnyOrderEntriesOf(mappings);
    }

    @Test
    void overOneMiBIs413ButLargeInvalidDtoStillValidatesAndOtherDtosKeep256KiB() {
        String hugeName = "x".repeat(1024 * 1024);
        create(body(Map.of()).replace("\"Flow\"", "\"" + hugeName + "\"")).expectStatus().isEqualTo(413);
        patch("{\"name\":\"" + hugeName + "\"}").expectStatus().isEqualTo(413);
        create(body(mappings()).replace("\"Flow\"", "\"" + "x".repeat(101) + "\"")).expectStatus().isBadRequest();
        http().post().uri("/test/binding/default-codec").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "x".repeat(300_000))).exchange().expectStatus().isEqualTo(413);
        assertThat(CREATES).hasValue(0); assertThat(PATCHES).hasValue(0);
    }

    @Test
    void createTypeReturnsFlatMetadataAndSelectedWorkflowWithoutChangingSharedProjection() {
        http().post().uri(typeUrl()).header(HttpHeaders.AUTHORIZATION, "Bearer test")
                .header("X-Request-Id", "create-type-wire-436").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"EPIC\",\"name\":\"Epic\",\"color\":\"purple\",\"workflowId\":\"" + WORKFLOW_ID + "\"}")
                .exchange().expectStatus().isCreated().expectBody().jsonPath("$.code").isEqualTo("EPIC")
                .jsonPath("$.shape").isEqualTo("square").jsonPath("$.workflowId").isEqualTo(WORKFLOW_ID.toString())
                .jsonPath("$.generation").exists().jsonPath("$.pendingOperationId").doesNotExist()
                .jsonPath("$.issueType").doesNotExist();
        assertThat(TYPE.get().getWorkflowId()).isEqualTo(WORKFLOW_ID.toString());
        assertThat(TYPE.get().getHeader().getRequestId()).isEqualTo("create-type-wire-436");
        assertThat(TYPE.get().getActorUserId()).isEqualTo(ACTOR.toString());
    }

    @Test
    void readPatCannotCreateOrRebindWorkflowsOrCreateAType() {
        when(contexts.mapToGatewayUserContext(any())).thenReturn(user(List.of("read")));
        create(body(Map.of())).expectStatus().isForbidden();
        patch("{\"version\":4,\"issueTypes\":[\"TASK\"]}").expectStatus().isForbidden();
        http().post().uri(typeUrl()).header(HttpHeaders.AUTHORIZATION, "Bearer test").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"code\":\"EPIC\",\"name\":\"Epic\",\"color\":\"purple\"}")
                .exchange().expectStatus().isForbidden();
        assertThat(CREATES).hasValue(0); assertThat(PATCHES).hasValue(0); assertThat(TYPES).hasValue(0);
    }

    @Test
    void nullAndUnknownPostFieldsAndUnsupportedPatchMediaNeverReachRpc() {
        for (String suffix : List.of(",\"statusMappings\":null", ",\"unknown\":1", ",\"statusMappings\":{\"BACKLOG\":null}")) {
            String value = body(Map.of()); value = value.substring(0, value.length() - 1) + suffix + "}";
            create(value).expectStatus().isBadRequest();
        }
        http().patch().uri(workflowUrl()).header(HttpHeaders.AUTHORIZATION, "Bearer test").contentType(MediaType.TEXT_PLAIN)
                .bodyValue("{\"version\":4,\"issueTypes\":[\"TASK\"]}").exchange().expectStatus().isEqualTo(415);
        assertThat(CREATES).hasValue(0); assertThat(PATCHES).hasValue(0);
    }

    private WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).responseTimeout(Duration.ofSeconds(15)).build();
    }
    private WebTestClient.ResponseSpec create(String body) {
        return http().post().uri("/api/v1/projects/" + PROJECT_ID + "/workflows").header(HttpHeaders.AUTHORIZATION, "Bearer test")
                .header("X-Request-Id", "binding-wire-436").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }
    private WebTestClient.ResponseSpec patch(String body) {
        return http().patch().uri(workflowUrl()).header(HttpHeaders.AUTHORIZATION, "Bearer test")
                .header("X-Request-Id", "binding-wire-436").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }
    private String workflowUrl() { return "/api/v1/projects/" + PROJECT_ID + "/workflows/" + WORKFLOW_ID; }
    private String typeUrl() { return "/api/v1/projects/" + PROJECT_ID + "/issue-types"; }
    private static GatewayUserContext user(List<String> scopes) {
        return GatewayUserContext.builder().userId(ACTOR.toString()).status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER)
                .tokenKind(GatewayTokenKind.PAT).tokenId(ACTOR.toString()).scopes(scopes).build();
    }
    private static String body(Map<String, String> mappings) {
        return JSON.writeValueAsString(Map.of("name", "Flow", "issueTypes", List.of("TASK"), "statuses", List.of(
                Map.of("statusKey", "TODO", "name", "To do", "category", "TODO", "sortOrder", 10)),
                "transitions", List.of(), "statusMappings", mappings));
    }
    private static Map<String, String> mappings() {
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < 5000; i++) result.put("S%063d".formatted(i), "T%063d".formatted(i));
        return result;
    }
    private static ProjectWorkflow workflow(List<String> types) {
        return ProjectWorkflow.newBuilder().setWorkflow(WorkflowResponse.newBuilder().setId(WORKFLOW_ID.toString())
                .setName("Flow").setVersion(5)).addAllIssueTypeKeys(types).build();
    }
    private static WorkflowBindingOperationResult answer() {
        return ANSWER.get() == null ? WorkflowBindingOperationResult.newBuilder().setResult(workflow(List.of("TASK"))).build() : ANSWER.get();
    }
    private static Server workflows() {
        try {
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(new ReactorWorkflowServiceGrpc.WorkflowServiceImplBase() {
                @Override public Mono<WorkflowBindingOperationResult> createProjectWorkflowWithBindings(Mono<CreateWorkflowRequest> request) {
                    return request.map(value -> { CREATES.incrementAndGet(); CREATE.set(value); return answer(); });
                }
                @Override public Mono<WorkflowBindingOperationResult> updateProjectWorkflowWithBindings(Mono<WorkflowMutationRequest> request) {
                    return request.map(value -> { PATCHES.incrementAndGet(); PATCH.set(value); return answer(); });
                }
                @Override public Mono<ProjectWorkflow> updateWorkflow(Mono<WorkflowMutationRequest> request) {
                    return request.map(value -> { LEGACY.incrementAndGet(); PATCH.set(value); return answer().getResult(); });
                }
            }).build().start();
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    private static Server projects() {
        try {
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(new ReactorProjectServiceGrpc.ProjectServiceImplBase() {
                @Override public Mono<CreateProjectIssueTypeWithWorkflowResult> createProjectIssueTypeWithWorkflow(Mono<ChangeProjectIssueTypeRequest> request) {
                    return request.map(value -> {
                        TYPES.incrementAndGet(); TYPE.set(value);
                        return CreateProjectIssueTypeWithWorkflowResult.newBuilder().setWorkflowId(WORKFLOW_ID.toString())
                                .setIssueType(ProjectIssueTypeResponse.newBuilder().setCode(value.getCode()).setName(value.getName())
                                        .setColor(value.getColor()).setShape("square").setLegacyIcon("check-square")
                                        .setGeneration(UUID.randomUUID().toString()).setPendingOperationId(UUID.randomUUID().toString())).build();
                    });
                }
            }).build().start();
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @RestController
    static class DefaultBodyProbe {
        @PostMapping("/test/binding/default-codec")
        Mono<Void> decode(@RequestBody Mono<DefaultBody> body) { return body.then(); }
    }
    record DefaultBody(String content) {}
}
