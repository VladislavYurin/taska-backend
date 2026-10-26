package ru.taska.transport.grpc;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.workflow.v1.*;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.transport.grpc.workflow.GrpcWorkflowServiceClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TargetTypeWorkflowReasonTest {
    @Test
    void incompatibleCurrentStatusHasStableReason() {
        var stub = mock(ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub.class);
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        when(stub.getWorkflowForProject(any(GetWorkflowForProjectRequest.class))).thenReturn(Mono.just(WorkflowResponse.newBuilder()
                .addStatuses(WorkflowStatus.newBuilder().setStatusKey("TODO")).build()));
        var client = new GrpcWorkflowServiceClient(stub, new IssueMapper(new tools.jackson.databind.ObjectMapper()));
        var issue = Issue.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID()).issueKey("TEST-1")
                .issueType(IssueType.BUG).statusKey("REVIEW").version(1).build();
        StepVerifier.create(client.requireStatusForType("r", "n", issue, UUID.randomUUID()))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getDetails())
                        .containsEntry("reason", "STATUS_NOT_IN_TARGET_WORKFLOW")).verify();
    }
}
