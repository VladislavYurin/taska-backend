package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.UUID;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.repository.*;
import ru.taska.transport.grpc.GrpcIssueServiceAdapter;
import ru.taska.transport.kafka.OutboxEventScheduler;
import ru.taska.util.RequestHasher;

/** Real issue PostgreSQL, production gRPC validation, workflow client, transactions and events. */
class InitialIssueStatusIT extends AbstractIT {
  @MockitoBean ReactorProjectServiceGrpc.ReactorProjectServiceStub projects;
  @MockitoBean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflows;
  @MockitoBean OutboxEventScheduler scheduler;
  @Autowired GrpcIssueServiceAdapter grpc;
  @Autowired IssueRepository issues;
  @Autowired IssueHistoryRepository history;
  @Autowired OutboxEventRepository outbox;
  @Autowired IdempotencyKeyRepository keys;
  @Autowired ProjectCounterRepository counters;
  static final UUID PROJECT = UUID.randomUUID(),
      ACTOR = UUID.randomUUID(),
      TOKEN = UUID.randomUUID();
  String key;

  @BeforeEach
  void prepare() {
    key = UUID.randomUUID().toString();
    when(projects.withDeadlineAfter(anyLong(), any())).thenReturn(projects);
    when(projects.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
        .thenReturn(Mono.just(role(ProjectRole.PROJECT_ROLE_MEMBER)));
    when(projects.getProjectKeyInternal(any(GetProjectKeyInternalRequest.class)))
        .thenReturn(Mono.just(ProjectKeyResponse.newBuilder().setProjectKey("INIT").build()));
    when(projects.listProjectIssueTypes(any(ProjectIssueTypesRequest.class)))
        .thenReturn(
            Mono.just(
                ListProjectIssueTypesResponse.newBuilder()
                    .addItems(ProjectIssueTypeResponse.newBuilder().setCode("REQUEST"))
                    .addItems(ProjectIssueTypeResponse.newBuilder().setCode("TASK"))
                    .build()));
    when(workflows.withDeadlineAfter(anyLong(), any())).thenReturn(workflows);
    when(workflows.getWorkflowForProject(any(GetWorkflowForProjectRequest.class)))
        .thenAnswer(
            invocation -> {
              GetWorkflowForProjectRequest request = invocation.getArgument(0);
              assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT.toString());
              assertThat(request.getBody().getActorUserId()).isEqualTo(ACTOR.toString());
              String status =
                  "REQUEST".equals(request.getBody().getIssueTypeKey()) ? "IN_REVIEW" : "TODO";
              return Mono.just(
                  WorkflowResponse.newBuilder()
                      .addStatuses(
                          ru.taska.api.workflow.v1.WorkflowStatus.newBuilder().setStatusKey(status))
                      .build());
            });
    outbox
        .deleteAll()
        .then(keys.deleteAll())
        .then(issues.deleteAll())
        .then(counters.deleteAll())
        .block();
  }

  @Test
  void customTypeUsesItsWorkflowAndPersistsActualStatusWithMcpAttribution() {
    var created = grpc.createIssue(Mono.just(request("REQUEST", "IN_REVIEW"))).block();
    assertThat(created.getStatusKey()).isEqualTo("IN_REVIEW");
    var stored = issues.findById(UUID.fromString(created.getId())).block();
    assertThat(stored.getStatusKey()).isEqualTo("IN_REVIEW");
    assertThat(stored.getIssueType().name()).isEqualTo("REQUEST");
    var events =
        history
            .findByIssueIdOrderByOccurredAtDesc(
                stored.getId(), org.springframework.data.domain.Limit.of(10))
            .collectList()
            .block();
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getPayload().path("statusKey").asText()).isEqualTo("IN_REVIEW");
    assertThat(events.getFirst().getChannel()).isEqualTo("MCP");
    assertThat(events.getFirst().getTokenId()).isEqualTo(TOKEN);
    var createdEvent =
        outbox
            .findAll()
            .filter(
                event ->
                    ru.taska.event.EventType.ISSUE_CREATED.getValue().equals(event.getEventType()))
            .blockFirst();
    assertThat(createdEvent.getPayload().path("statusKey").asText()).isEqualTo("IN_REVIEW");
    assertThat(createdEvent.getPayload().path("channel").asText()).isEqualTo("MCP");
    assertThat(createdEvent.getPayload().toString()).doesNotContain(TOKEN.toString());
    assertThat(keys.count().block()).isEqualTo(1);
  }

  @Test
  void omittedStatusKeepsTodoAndDoesNotResolveWorkflow() {
    when(workflows.getWorkflowForProject(any(GetWorkflowForProjectRequest.class)))
        .thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));
    assertThat(grpc.createIssue(Mono.just(request("REQUEST", null))).block().getStatusKey())
        .isEqualTo("TODO");
    verify(workflows, never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
  }

  @Test
  void validStatusFromAnotherTypeIsRejectedBeforeAnyWriteOrCounter() {
    rejects(
        request("TASK", "IN_REVIEW"), Status.Code.FAILED_PRECONDITION, "STATUS_NOT_IN_WORKFLOW");
    assertEmpty();
  }

  @Test
  void unregisteredTypeIsRejectedBeforeResolvingAWorkflow() {
    rejects(request("NOPE", "IN_REVIEW"), Status.Code.INVALID_ARGUMENT, null);
    verify(workflows, never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
    assertEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "in_review", "BAD-KEY", "A"})
  void invalidStatusFormatIsRejectedBeforeWorkflowOrWrites(String status) {
    rejects(request("REQUEST", status), Status.Code.INVALID_ARGUMENT, null);
    verify(workflows, never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
    assertEmpty();
  }

  @Test
  void workflowOutageAndNonWriterCannotCreateOrLearnWorkflow() {
    when(workflows.getWorkflowForProject(any(GetWorkflowForProjectRequest.class)))
        .thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));
    rejects(request("REQUEST", "IN_REVIEW"), Status.Code.UNAVAILABLE, null);
    assertEmpty();
    clearInvocations(workflows);
    when(projects.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
        .thenReturn(Mono.just(role(ProjectRole.PROJECT_ROLE_VIEWER)));
    rejects(request("REQUEST", "IN_REVIEW"), Status.Code.PERMISSION_DENIED, null);
    verify(workflows, never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
    assertEmpty();
  }

  @Test
  void sameRequestReplaysAndChangedStatusOrEveryPlanningFieldConflicts() {
    var body =
        request("REQUEST", "IN_REVIEW").getBody().toBuilder()
            .setStoryPoints(3)
            .setStartDate("2026-10-01")
            .setDueDate("2026-10-30")
            .setOriginalEstimateMinutes(60)
            .setRemainingEstimateMinutes(30)
            .build();
    var original = request("REQUEST", "IN_REVIEW").toBuilder().setBody(body).build();
    var first = grpc.createIssue(Mono.just(original)).block();
    assertThat(grpc.createIssue(Mono.just(original)).block().getId()).isEqualTo(first.getId());
    for (var changed :
        List.of(
            body.toBuilder().setStatusKey("DONE").build(),
            body.toBuilder().setStoryPoints(4).build(),
            body.toBuilder().setStartDate("2026-10-02").build(),
            body.toBuilder().setDueDate("2026-10-29").build(),
            body.toBuilder().setOriginalEstimateMinutes(61).build(),
            body.toBuilder().setRemainingEstimateMinutes(31).build()))
      rejects(original.toBuilder().setBody(changed).build(), Status.Code.FAILED_PRECONDITION, null);
    assertThat(issues.count().block()).isEqualTo(1);
    assertThat(keys.count().block()).isEqualTo(1);
    assertThat(history.count().block()).isEqualTo(1);
    assertThat(outbox.count().block()).isEqualTo(2);
    verify(workflows, times(1)).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
  }

  @Test
  void legacyHashRetriesUseCompleteStoredResponseAndRejectChangedPlanning() {
    var original =
        request("TASK", null).toBuilder()
            .setBody(request("TASK", null).getBody().toBuilder().setOriginalEstimateMinutes(60))
            .build();
    var first = grpc.createIssue(Mono.just(original)).block();
    var stored = keys.findByUserIdAndKey(ACTOR, key).block();
    stored.setRequestHash(
        RequestHasher.hashIssueCreateRequest(
            PROJECT,
            ru.taska.domain.IssueType.TASK,
            "Initial status",
            "",
            ru.taska.domain.entity.IssuePriority.MEDIUM,
            ACTOR));
    keys.save(stored).block();
    assertThat(grpc.createIssue(Mono.just(original)).block().getId()).isEqualTo(first.getId());
    rejects(
        original.toBuilder()
            .setBody(original.getBody().toBuilder().setOriginalEstimateMinutes(61))
            .build(),
        Status.Code.FAILED_PRECONDITION,
        null);
    assertThat(issues.count().block()).isEqualTo(1);
    assertThat(history.count().block()).isEqualTo(1);
    assertThat(outbox.count().block()).isEqualTo(2);
  }

  private CreateIssueRequest request(String type, String status) {
    var body =
        CreateIssueRequestBody.newBuilder()
            .setIdempotencyKey(key)
            .setProjectId(PROJECT.toString())
            .setReporterId(ACTOR.toString())
            .setIssueTypeKey(type)
            .setSummary("Initial status")
            .setDescription("")
            .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM);
    if (status != null) body.setStatusKey(status);
    return CreateIssueRequest.newBuilder()
        .setHeader(
            Header.newBuilder()
                .setRequestId("initial-status")
                .setNodeId("api-gateway")
                .setActionChannel("MCP")
                .setTokenId(TOKEN.toString()))
        .setBody(body)
        .build();
  }

  private CheckProjectMemberRoleResponse role(ProjectRole role) {
    return CheckProjectMemberRoleResponse.newBuilder()
        .setProjectExists(true)
        .setIsMember(true)
        .setProjectArchived(false)
        .setRole(role)
        .build();
  }

  private void rejects(CreateIssueRequest request, Status.Code code, String reason) {
    StepVerifier.create(grpc.createIssue(Mono.just(request)))
        .expectErrorSatisfies(
            error -> {
              assertThat(Status.fromThrowable(error).getCode()).isEqualTo(code);
              if (reason != null)
                assertThat(GrpcErrorDetails.decode(((StatusRuntimeException) error).getTrailers()))
                    .containsEntry("reason", reason);
            })
        .verify();
  }

  private void assertEmpty() {
    assertThat(issues.count().block()).isZero();
    assertThat(keys.count().block()).isZero();
    assertThat(history.count().block()).isZero();
    assertThat(outbox.count().block()).isZero();
    assertThat(counters.count().block()).isZero();
  }
}
