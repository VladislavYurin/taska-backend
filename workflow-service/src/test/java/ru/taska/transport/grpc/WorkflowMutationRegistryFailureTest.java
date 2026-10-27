package ru.taska.transport.grpc;

import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.*;
import ru.taska.service.WorkflowMutationResponse;
import static org.mockito.Mockito.*;

class WorkflowMutationRegistryFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"create", "status", "transition"})
    void registryOutageFailsBeforeAnyMutation(String operation) {
        var delegate = mock(GrpcWorkflowService.class);
        var mutations = mock(WorkflowMutationGrpcService.class);
        var registry = mock(WorkflowMutationResponse.class);
        var adapter = new GrpcWorkflowServiceAdapter(delegate, mutations, registry, mock(WorkflowBindingGrpcService.class));
        String project = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        var header = Header.newBuilder().setRequestId("registry-outage").setNodeId("gateway").build();
        when(registry.issueTypes(header, project, actor)).thenReturn(Mono.error(Status.UNAVAILABLE
                .withDescription("Project registry is unavailable").asRuntimeException()));
        Mono<WorkflowResponse> result = switch (operation) {
            case "create" -> adapter.createWorkflow(Mono.just(CreateWorkflowRequest.newBuilder().setHeader(header)
                    .setBody(CreateWorkflowRequestBody.newBuilder().setProjectId(project).setActorUserId(actor)).build()));
            case "status" -> adapter.addWorkflowStatus(Mono.just(AddWorkflowStatusRequest.newBuilder().setHeader(header)
                    .setBody(AddWorkflowStatusRequestBody.newBuilder().setProjectId(project).setActorUserId(actor)).build()));
            default -> adapter.addWorkflowTransition(Mono.just(AddWorkflowTransitionRequest.newBuilder().setHeader(header)
                    .setBody(AddWorkflowTransitionRequestBody.newBuilder().setProjectId(project).setActorUserId(actor)).build()));
        };
        StepVerifier.create(result).expectErrorMatches(error -> Status.fromThrowable(error).getCode()
                == Status.Code.UNAVAILABLE).verify();
        verifyNoInteractions(delegate, mutations);
    }
}
