package ru.taska.transport.grpc.project;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.registry.v1.*;

@Component
@RequiredArgsConstructor
public class WorkflowProjectOperationClient {
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projects;
    public Mono<ProjectTypeOperationGate> gate(UUID project) {
        return stub().getGate(TypeOperationProjectRequest.newBuilder().setProjectId(project.toString()).setHeader(header()).build());
    }
    public Mono<Void> reserve(UUID project, UUID operation) { return stub().reserveWorkflowBindings(key(project,operation)).then(); }
    public Mono<Void> release(UUID project, UUID operation) { return stub().releaseWorkflowBindings(key(project,operation)).then(); }
    public Mono<IssueTypeCreationIntent> creation(UUID project, UUID operation) {
        return ReactorProjectIssueTypeCreationJournalGrpc.newReactorStub(projects.getChannel())
                .withDeadlineAfter(5,TimeUnit.SECONDS).get(key(project,operation));
    }
    private ReactorProjectTypeOperationsGrpc.ReactorProjectTypeOperationsStub stub() {
        return ReactorProjectTypeOperationsGrpc.newReactorStub(projects.getChannel()).withDeadlineAfter(10,TimeUnit.SECONDS);
    }
    public static TypeOperationKey key(UUID project, UUID operation) {
        return TypeOperationKey.newBuilder().setProjectId(project.toString()).setOperationId(operation.toString()).setHeader(header()).build();
    }
    private static Header header() { return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("workflow-service").build(); }
}
