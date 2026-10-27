package ru.taska.transport.grpc.issue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.registry.v1.ReactorWorkflowBindingIssueMigrationGrpc;
import ru.taska.api.registry.v1.TypeOperationOutcome;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

@Component
@RequiredArgsConstructor
public class WorkflowBindingIssueClient {
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issues;
    public Mono<TypeOperationOutcome> apply(UUID project, UUID operation) { return stub().apply(WorkflowProjectOperationClient.key(project,operation)); }
    public Mono<TypeOperationOutcome> cancel(UUID project, UUID operation) { return stub().cancel(WorkflowProjectOperationClient.key(project,operation)); }
    public Mono<Void> release(UUID project, UUID operation) {
        return stub().release(WorkflowProjectOperationClient.key(project,operation)).flatMap(result -> {
            if (result.getState()==ru.taska.api.registry.v1.TypeOperationState.TYPE_OPERATION_STATE_APPLIED
                    || result.getState()==ru.taska.api.registry.v1.TypeOperationState.TYPE_OPERATION_STATE_CANCELLED)
                return Mono.<Void>empty();
            return Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.UNAVAILABLE,
                    "Issue-service has not acknowledged workflow fence release",
                    java.util.Map.of("reason","WORKFLOW_BINDING_RELEASE_PENDING")));
        });
    }
    private ReactorWorkflowBindingIssueMigrationGrpc.ReactorWorkflowBindingIssueMigrationStub stub() {
        return ReactorWorkflowBindingIssueMigrationGrpc.newReactorStub(issues.getChannel()).withDeadlineAfter(90,TimeUnit.SECONDS);
    }
}
