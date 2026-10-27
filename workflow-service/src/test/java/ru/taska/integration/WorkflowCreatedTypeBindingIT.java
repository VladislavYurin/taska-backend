package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.registry.v1.*;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.WorkflowCreatedTypeBindingService;
import ru.taska.service.WorkflowResolver;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "spring.grpc.server.address=127.0.0.1:0","app.workflow-binding-changes.recovery-interval=1h"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowCreatedTypeBindingIT extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(15);
    private static final UUID DEFAULT=UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Autowired WorkflowCreatedTypeBindingService service;
    @Autowired WorkflowResolver resolver;
    @MockitoBean WorkflowProjectOperationClient operations;
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean ProjectRoleChecker roles;
    private UUID project,operation,actor,generation,target;
    private final AtomicReference<IssueTypeCreationIntent> intent=new AtomicReference<>();
    private final AtomicReference<List<ProjectIssueTypeResponse>> registry=new AtomicReference<>();

    @BeforeEach
    void prepare() {
        project=UUID.randomUUID();operation=UUID.randomUUID();actor=UUID.randomUUID();generation=UUID.randomUUID();target=UUID.randomUUID();
        registry.set(ru.taska.RegistryFixtures.types(project,IssueType.TASK,IssueType.BUG,IssueType.STORY));
        intent.set(IssueTypeCreationIntent.newBuilder().setProjectId(project.toString()).setOperationId(operation.toString())
                .setActorUserId(actor.toString()).setCode("EPIC").setGeneration(generation.toString()).setState("PENDING")
                .setOriginalHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("project-service"))
                .setFirstTypeCode("TASK").setFirstTypeGeneration(registry.get().getFirst().getGeneration()).build());
        when(operations.creation(any(),any())).thenAnswer(call -> Mono.defer(() -> Mono.just(intent.get())));
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.newBuilder().setActive(true)
                .setOperationId(operation.toString()).setKind("CREATE_TYPE").build()));
        when(projects.getIssueTypesInternal(any())).thenAnswer(call -> Mono.defer(() -> Mono.just(registry.get())));
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any())).thenReturn(Mono.empty());
        graph(target,project);
        databaseClient.sql("INSERT INTO taska.workflow_bindings(project_id,issue_type,workflow_id,issue_type_generation) VALUES(:project,'TASK',:workflow,:generation)")
                .bind("project",project).bind("workflow",target).bind("generation",UUID.fromString(registry.get().getFirst().getGeneration())).then().block(TIMEOUT);
    }

    @Test
    void implicitMainWorkflowBindingIsAtomicAndInvisibleUntilRegistryPublish() {
        var result=service.apply(project,operation).block(TIMEOUT);
        assertThat(result.getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_APPLIED);
        assertThat(result.getWorkflowId()).isEqualTo(target.toString());
        assertThat(binding()).isEqualTo(target);assertThat(version(target)).isEqualTo(2);
        assertThatThrownBy(() -> resolver.resolveWorkflow(project,"EPIC").block(TIMEOUT)).isInstanceOf(DomainException.class);
        registry.set(java.util.stream.Stream.concat(registry.get().stream(),java.util.stream.Stream.of(
                ProjectIssueTypeResponse.newBuilder().setCode("EPIC").setGeneration(generation.toString()).build())).toList());
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.getDefaultInstance()));
        assertThat(resolver.resolveWorkflow(project,"EPIC").block(TIMEOUT).getId()).isEqualTo(target);
    }

    @Test
    void receiptReplayAndCancelAfterAppliedNeverRecheckPermissionsOrBumpAgain() {
        service.apply(project,operation).block(TIMEOUT);
        intent.set(intent.get().toBuilder().setState("COMPLETED").build());
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any())).thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"revoked")));
        assertThat(service.apply(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_APPLIED);
        assertThat(service.cancel(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_APPLIED);
        assertThat(version(target)).isEqualTo(2);assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void explicitOwnedEmptyGraphIsUsableAndDefaultDoesNotChangeItsVersion() {
        UUID empty=UUID.randomUUID();graph(empty,project);
        intent.set(intent.get().toBuilder().setWorkflowId(empty.toString()).build());
        assertThat(service.apply(project,operation).block(TIMEOUT).getWorkflowId()).isEqualTo(empty.toString());
        assertThat(binding()).isEqualTo(empty);assertThat(version(empty)).isEqualTo(2);
        operation=UUID.randomUUID();generation=UUID.randomUUID();int before=version(DEFAULT);
        intent.set(intent.get().toBuilder().setOperationId(operation.toString()).setCode("DEFAULTED").setGeneration(generation.toString()).setWorkflowId(DEFAULT.toString()).build());
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.newBuilder().setActive(true).setKind("CREATE_TYPE").setOperationId(operation.toString()).build()));
        assertThat(service.apply(project,operation).block(TIMEOUT).getWorkflowId()).isEqualTo(DEFAULT.toString());
        assertThat(version(DEFAULT)).isEqualTo(before);
    }

    @Test
    void foreignWorkflowAndStaleFirstGenerationFailBeforeBinding() {
        UUID foreign=UUID.randomUUID();graph(foreign,UUID.randomUUID());
        intent.set(intent.get().toBuilder().setWorkflowId(foreign.toString()).build());
        rejected("WORKFLOW_NOT_IN_PROJECT");assertThat(binding()).isNull();assertThat(receiptCount()).isZero();
        intent.set(intent.get().toBuilder().clearWorkflowId().setFirstTypeGeneration(UUID.randomUUID().toString()).build());
        rejected("ISSUE_TYPES_CHANGED");assertThat(binding()).isNull();assertThat(version(target)).isEqualTo(1);
    }

    @Test
    void cancellationReceiptPermanentlyPreventsLateApply() {
        intent.set(intent.get().toBuilder().setState("CANCELLING").build());
        assertThat(service.cancel(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        intent.set(intent.get().toBuilder().setState("PENDING").build());
        assertThat(service.apply(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        assertThat(binding()).isNull();assertThat(version(target)).isEqualTo(1);
    }

    @Test
    void missingOrForeignProjectGateCannotPublishABinding() {
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.getDefaultInstance()));
        rejected("ISSUE_TYPE_CREATION_NOT_RESERVED");assertThat(binding()).isNull();
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.newBuilder().setActive(true).setKind("CREATE_TYPE").setOperationId(UUID.randomUUID().toString()).build()));
        rejected("ISSUE_TYPE_CREATION_NOT_RESERVED");assertThat(receiptCount()).isZero();
    }

    @Test
    void receiptInsertFailureRollsBackBindingAndWorkflowVersionThenRetrySucceeds() {
        databaseClient.sql("CREATE FUNCTION taska.fail_type_creation_receipt() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture receipt failure''; END'").then().block(TIMEOUT);
        databaseClient.sql("CREATE TRIGGER fail_type_creation_receipt BEFORE INSERT ON taska.issue_type_creation_workflow_receipts FOR EACH ROW EXECUTE FUNCTION taska.fail_type_creation_receipt()").then().block(TIMEOUT);
        try {
            assertThatThrownBy(() -> service.apply(project,operation).block(TIMEOUT)).isInstanceOf(RuntimeException.class);
            assertThat(binding()).isNull();assertThat(version(target)).isEqualTo(1);assertThat(receiptCount()).isZero();
        } finally {
            databaseClient.sql("DROP TRIGGER fail_type_creation_receipt ON taska.issue_type_creation_workflow_receipts").then().block(TIMEOUT);
            databaseClient.sql("DROP FUNCTION taska.fail_type_creation_receipt()").then().block(TIMEOUT);
        }
        assertThat(service.apply(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_APPLIED);
        assertThat(version(target)).isEqualTo(2);assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void concurrentAppliesBindOnceAndIncrementTheOwnedGraphOnce() {
        var results=Flux.merge(service.apply(project,operation),service.apply(project,operation)).collectList().block(TIMEOUT);
        assertThat(results).hasSize(2);assertThat(results).allSatisfy(result -> assertThat(result.getWorkflowId()).isEqualTo(target.toString()));
        assertThat(version(target)).isEqualTo(2);assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void actorRevocationRejectsFreshApplyButCannotBlockCancellation() {
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any())).thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"revoked")));
        assertThatThrownBy(() -> service.apply(project,operation).block(TIMEOUT)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED));
        intent.set(intent.get().toBuilder().setState("CANCELLING").build());
        assertThat(service.cancel(project,operation).block(TIMEOUT).getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        assertThat(binding()).isNull();
    }

    private void graph(UUID id,UUID owner) {
        databaseClient.sql("INSERT INTO taska.workflows(id,name,owner_project_id,version,is_active) VALUES(:id,'Own empty graph',:owner,1,true)")
                .bind("id",id).bind("owner",owner).then().block(TIMEOUT);
    }
    private UUID binding() {
        return databaseClient.sql("SELECT workflow_id FROM taska.workflow_bindings WHERE project_id=:project AND issue_type='EPIC'")
                .bind("project",project).map(row -> row.get(0,UUID.class)).one().block(TIMEOUT);
    }
    private int version(UUID workflow) {
        return databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:workflow").bind("workflow",workflow)
                .map(row -> row.get(0,Integer.class)).one().block(TIMEOUT);
    }
    private long receiptCount() {
        return databaseClient.sql("SELECT count(*) FROM taska.issue_type_creation_workflow_receipts WHERE operation_id=:operation")
                .bind("operation",operation).map(row -> row.get(0,Long.class)).one().block(TIMEOUT);
    }
    private void rejected(String reason) {
        assertThatThrownBy(() -> service.apply(project,operation).block(TIMEOUT)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getDetails()).containsEntry("reason",reason));
    }
}
