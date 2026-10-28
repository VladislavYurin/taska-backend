package ru.taska.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.protobuf.Empty;
import io.grpc.Status;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.DeleteProjectIssueTypeResult;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.*;
import ru.taska.domain.dto.DeleteProjectIssueTypeRequestDto;

@ExtendWith(MockitoExtension.class)
class GrpcProjectIssueTypeDeletionClientTest {
  @Mock ReactorProjectServiceGrpc.ReactorProjectServiceStub stub;
  @Mock GrpcClientProperties properties;
  @Mock GrpcClientProperties.Service projectProperties;
  private GrpcProjectIssueTypeClient client;
  private GatewayContext context;
  private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), generation = UUID.randomUUID();
  private final String tokenId = UUID.randomUUID().toString();

  @BeforeEach
  void prepare() {
    client = new GrpcProjectIssueTypeClient(stub, properties);
    var user = GatewayUserContext.builder().userId(actor.toString()).email("member@example.test")
        .displayName("Member").status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER)
        .tokenKind(GatewayTokenKind.PAT).tokenId(tokenId).scopes(List.of("read", "write")).build();
    context = new GatewayContext("move-433", "api-gateway", user, ActionChannel.MCP);
  }

  @Test
  void moveUsesBoundedLongDeadlineAndPreservesCommandAndTrustedAttribution() {
    when(stub.withDeadlineAfter(120, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.deleteProjectIssueTypeWithMigration(any(ChangeProjectIssueTypeRequest.class)))
        .thenReturn(Mono.just(DeleteProjectIssueTypeResult.newBuilder().setCompleted(true).build()));
    var body = new DeleteProjectIssueTypeRequestDto();
    body.setMoveIssuesTo("TASK");
    body.setStatusMappings(Map.of("TRIAGE", "TODO", "REVIEW", "IN_PROGRESS"));
    body.setExpectedGeneration(generation);

    StepVerifier.create(client.delete(project, "REQUEST", body, context)).verifyComplete();

    var capture = ArgumentCaptor.forClass(ChangeProjectIssueTypeRequest.class);
    verify(stub).deleteProjectIssueTypeWithMigration(capture.capture());
    var sent = capture.getValue();
    assertThat(sent.getProjectId()).isEqualTo(project.toString());
    assertThat(sent.getActorUserId()).isEqualTo(actor.toString());
    assertThat(sent.getCode()).isEqualTo("REQUEST");
    assertThat(sent.getMoveIssuesTo()).isEqualTo("TASK");
    assertThat(sent.getStatusMappingsMap()).containsExactlyInAnyOrderEntriesOf(body.getStatusMappings());
    assertThat(sent.getExpectedGeneration()).isEqualTo(generation.toString());
    assertThat(sent.getHeader().getRequestId()).isEqualTo("move-433");
    assertThat(sent.getHeader().getActionChannel()).isEqualTo("MCP");
    assertThat(sent.getHeader().getTokenId()).isEqualTo(tokenId);
    verify(stub).withDeadlineAfter(120, TimeUnit.SECONDS);
    verifyNoInteractions(properties, projectProperties);
  }

  @Test
  void bodylessAndEmptyBodyDeletionUseSameLongDeadlineAsMigration() {
    when(stub.withDeadlineAfter(120, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.deleteProjectIssueType(any(ChangeProjectIssueTypeRequest.class)))
        .thenReturn(Mono.just(Empty.getDefaultInstance()));

    StepVerifier.create(client.delete(project, "REQUEST", context)).verifyComplete();
    StepVerifier.create(client.delete(project, "REQUEST", new DeleteProjectIssueTypeRequestDto(), context)).verifyComplete();

    var capture = ArgumentCaptor.forClass(ChangeProjectIssueTypeRequest.class);
    verify(stub, times(2)).deleteProjectIssueType(capture.capture());
    for (var sent : capture.getAllValues()) {
      assertThat(sent.hasMoveIssuesTo()).isFalse();
      assertThat(sent.hasExpectedGeneration()).isFalse();
      assertThat(sent.getStatusMappingsMap()).isEmpty();
      assertThat(sent.getCode()).isEqualTo("REQUEST");
    }
    verify(stub, times(2)).withDeadlineAfter(120, TimeUnit.SECONDS);
    verifyNoInteractions(properties, projectProperties);
  }

  @Test
  void moveTransportFailureIsPropagatedWithoutAnAutomaticRetry() {
    when(stub.withDeadlineAfter(120, TimeUnit.SECONDS)).thenReturn(stub);
    var unavailable = Status.UNAVAILABLE.withDescription("Deletion pending").asRuntimeException();
    when(stub.deleteProjectIssueTypeWithMigration(any(ChangeProjectIssueTypeRequest.class)))
        .thenReturn(Mono.error(unavailable));
    var body = new DeleteProjectIssueTypeRequestDto();
    body.setMoveIssuesTo("TASK");

    StepVerifier.create(client.delete(project, "REQUEST", body, context)).expectErrorMatches(e -> e == unavailable).verify();
    verify(stub).deleteProjectIssueTypeWithMigration(any(ChangeProjectIssueTypeRequest.class));
  }

  @Test
  void typedRejectionPreservesLargeDetailsWithoutGrpcTrailers() {
    when(stub.withDeadlineAfter(120, TimeUnit.SECONDS)).thenReturn(stub);
    var keys = "STATUS_" + "A".repeat(20000);
    var details = Map.of("reason", "ISSUE_STATUSES_UNMAPPED", "statusKeys", keys,
        "targetStatusKeys", "TODO,DONE");
    when(stub.deleteProjectIssueTypeWithMigration(any(ChangeProjectIssueTypeRequest.class)))
        .thenReturn(Mono.just(DeleteProjectIssueTypeResult.newBuilder()
            .setRejectionStatus("FAILED_PRECONDITION").setRejectionMessage("Map the issue statuses")
            .putAllRejectionDetails(details).build()));
    var body = new DeleteProjectIssueTypeRequestDto();
    body.setMoveIssuesTo("TASK");
    StepVerifier.create(client.delete(project, "REQUEST", body, context))
        .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ru.taska.exception.DomainException.class,
            rejected -> {
              assertThat(rejected.getStatus()).isEqualTo(ru.taska.exception.DomainStatus.FAILED_PRECONDITION);
              assertThat(rejected.getDetails()).containsExactlyInAnyOrderEntriesOf(details);
            })).verify();
    verify(stub, never()).deleteProjectIssueType(any(ChangeProjectIssueTypeRequest.class));
  }

  @Test
  void nullMappingValuesFailAsBadRequestBeforeProtobufConstruction() {
    var body = new DeleteProjectIssueTypeRequestDto();
    body.setMoveIssuesTo("TASK");
    var mappings = new LinkedHashMap<String, String>();
    mappings.put("TRIAGE", null);
    body.setStatusMappings(mappings);

    StepVerifier.create(client.delete(project, "REQUEST", body, context))
        .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
            status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))).verify();
    verifyNoInteractions(stub, properties, projectProperties);
  }

  @Test
  void publicGenerationIsOptionalAndPendingOperationIdentityIsNeverMapped() {
    var old = ProjectIssueTypeResponse.newBuilder().setCode("REQUEST").setName("Request")
        .setColor("teal").setIcon("headset").setSortOrder(40).build();
    assertThat(GrpcProjectIssueTypeClient.toRest(old).getGeneration()).isNull();
    var modern = old.toBuilder().setGeneration(generation.toString())
        .setPendingOperationId(UUID.randomUUID().toString()).build();
    var mapped = GrpcProjectIssueTypeClient.toRest(modern);
    assertThat(mapped.getGeneration()).isEqualTo(generation);
    var json = new tools.jackson.databind.ObjectMapper().valueToTree(mapped);
    assertThat(json.path("generation").asText()).isEqualTo(generation.toString());
    assertThat(json.has("pendingOperationId")).isFalse();
    assertThat(json.has("pending_operation_id")).isFalse();
  }

  private void legacyDeadline() {
    when(properties.projectService()).thenReturn(projectProperties);
    when(projectProperties.deadlineDuration()).thenReturn(Duration.ofSeconds(3));
    when(stub.withDeadlineAfter(3000, TimeUnit.MILLISECONDS)).thenReturn(stub);
  }
}
