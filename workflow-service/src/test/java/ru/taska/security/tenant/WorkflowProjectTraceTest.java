package ru.taska.security.tenant;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalRequest;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowProjectTraceTest {
    @Test
    void metadataLookupPreservesVerifiedParentTraceAcrossSubscriptions() {
        var stub = mock(ReactorProjectServiceGrpc.ReactorProjectServiceStub.class);
        when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        when(stub.getProjectInfoBatchInternal(any(GetProjectInfoBatchInternalRequest.class)))
                .thenReturn(Mono.just(GetProjectInfoBatchInternalResponse.getDefaultInstance()));
        var client = new GrpcProjectServiceClient(stub);
        var project = UUID.randomUUID();
        var lookup = client.getIssueTypesInternal(project);
        for (var request : new String[]{"context-first", "context-second"}) {
            var parent = new VerifiedTenantContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    request, "getWorkflowForProject", Set.of("read"), new byte[0]);
            assertThat(RpcContextBridge.install(lookup, parent).block()).isEmpty();
        }
        var captured = ArgumentCaptor.forClass(GetProjectInfoBatchInternalRequest.class);
        verify(stub, times(2)).getProjectInfoBatchInternal(captured.capture());
        assertThat(captured.getAllValues()).extracting(r -> r.getHeader().getRequestId())
                .containsExactly("context-first", "context-second");
        assertThat(captured.getAllValues()).allSatisfy(r -> {
            assertThat(r.getHeader().getNodeId()).isEqualTo("workflow-service");
            assertThat(r.getBody().getProjectIdsList()).containsExactly(project.toString());
        });
    }
}
