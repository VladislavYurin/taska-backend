package ru.taska.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.ProjectTypeOperationGate;
import ru.taska.api.registry.v1.TypeOperationOutcome;
import ru.taska.api.registry.v1.TypeOperationState;
import ru.taska.transport.grpc.issue.WorkflowBindingIssueClient;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

/** Existing graph tests isolate remote participants; the new saga suite tests their durable protocol. */
abstract class LegacyWorkflowOperationsFixture extends AbstractIT {
    @MockitoBean WorkflowProjectOperationClient typeOperations;
    @MockitoBean WorkflowBindingIssueClient bindingIssues;
    @org.springframework.beans.factory.annotation.Autowired ru.taska.transport.grpc.project.ProjectRoleChecker legacyRoles;

    @BeforeEach
    void isolatedOperationParticipants() {
        when(legacyRoles.checkProjectRole(any(),any(),any(),any(),any())).thenReturn(Mono.empty());
        when(typeOperations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.getDefaultInstance()));
        when(typeOperations.reserve(any(),any())).thenReturn(Mono.empty());
        when(typeOperations.release(any(),any())).thenReturn(Mono.empty());
        when(bindingIssues.apply(any(),any())).thenReturn(Mono.just(TypeOperationOutcome.newBuilder()
                .setState(TypeOperationState.TYPE_OPERATION_STATE_APPLIED).build()));
        when(bindingIssues.cancel(any(),any())).thenReturn(Mono.just(TypeOperationOutcome.newBuilder()
                .setState(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED).build()));
        when(bindingIssues.release(any(),any())).thenReturn(Mono.empty());
    }
}
