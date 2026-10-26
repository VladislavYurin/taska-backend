package ru.taska.transport.grpc.issue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest;
import ru.taska.api.issue.v1.MigrateWorkflowIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.dto.WorkflowMutation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowIssueMigrationClientTest {
    @Test
    void deletingStatusSendsExplicitSourceWhileWorkflowDeletionRetainsLegacyScope() {
        var stub = mock(ReactorIssueServiceGrpc.ReactorIssueServiceStub.class);
        when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        when(stub.migrateWorkflowIssues(any(MigrateWorkflowIssuesRequest.class)))
                .thenReturn(Mono.just(MigrateWorkflowIssuesResponse.newBuilder().setMovedCount(2).build()));
        var client = new WorkflowIssueMigrationClient(stub);
        var command = new WorkflowMutation("r", "n", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, null, null, null, null, null, null, null, Map.of());
        assertThat(client.migrate(command, List.of(IssueType.TASK), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE"), List.of("REVIEW")).block()).isEqualTo(2);
        assertThat(client.migrate(command, List.of(IssueType.TASK), List.of("TODO", "DONE"),
                Map.of("REVIEW", "DONE")).block()).isEqualTo(2);
        var request = ArgumentCaptor.forClass(MigrateWorkflowIssuesRequest.class);
        verify(stub, times(2)).migrateWorkflowIssues(request.capture());
        assertThat(request.getAllValues().get(0).getSourceStatusKeysList()).containsExactly("REVIEW");
        assertThat(request.getAllValues().get(1).getSourceStatusKeysList()).isEmpty();
        assertThat(request.getValue().getIssueTypeKeysList()).containsExactly("TASK");
        assertThat(request.getValue().getStatusMappingsMap()).containsEntry("REVIEW", "DONE");
    }
}
