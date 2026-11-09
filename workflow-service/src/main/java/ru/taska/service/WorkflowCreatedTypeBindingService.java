package ru.taska.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.ProjectRole;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

/** An atomic binding/receipt; project-service publishes the new registry type only after APPLIED. */
@Service
@RequiredArgsConstructor
public class WorkflowCreatedTypeBindingService {
    private final WorkflowWriteFence fence;
    private final WorkflowProjectOperationClient operations;
    private final GrpcProjectServiceClient projects;
    private final ProjectRoleChecker roles;
    private final WorkflowRepository workflows;
    private final WorkflowBindingRepository bindings;
    private final WorkflowProperties properties;
    private final DatabaseClient database;

    @Transactional
    public Mono<CreatedTypeBindingOutcome> apply(UUID project, UUID operation) {
        return fence.lockProject(project).then(Mono.defer(() -> receipt(project,operation)))
                .switchIfEmpty(Mono.defer(() -> fence.checkLocalAvailable(project)
                        .then(operations.creation(project,operation)).flatMap(intent -> {
                            requireIdentity(intent,project,operation);
                            if (!intent.getState().equals("PENDING")) return Mono.error(failed("ISSUE_TYPE_CREATION_NOT_PENDING","Only a pending registry creation can bind a type"));
                            UUID actor=parse(intent.getActorUserId()),generation=parse(intent.getGeneration());
                            if (!intent.getCode().matches("^[A-Z][A-Z0-9_]{1,31}$")) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid issue type key"));
                            return operations.gate(project).flatMap(gate -> {
                                if (!gate.getActive() || !gate.getOperationId().equals(operation.toString()) || !gate.getKind().equals("CREATE_TYPE"))
                                    return Mono.error(failed("ISSUE_TYPE_CREATION_NOT_RESERVED","Project creation reservation is missing"));
                                return roles.checkProjectWriteRole(intent.getOriginalHeader().getRequestId(),intent.getOriginalHeader().getNodeId(),
                                                project,actor,java.util.Set.of(ProjectRole.ADMIN))
                                        .then(projects.getIssueTypesInternal(project)).flatMap(registry -> {
                                            WorkflowWriteFence.requireWritableRegistry(registry);
                                            if (registry.stream().anyMatch(type -> type.getCode().equals(intent.getCode())))
                                                return Mono.error(failed("ISSUE_TYPE_ALREADY_REGISTERED","The new type has already been registered without its creation receipt"));
                                            return choose(project,intent,registry).flatMap(target -> workflows.findByIdForUpdate(target.getId()))
                                                    .filter(WorkflowEntity::isActive)
                                                    .switchIfEmpty(Mono.error(failed("WORKFLOW_NOT_AVAILABLE","Target workflow is unavailable")))
                                                    .flatMap(target -> bind(project,operation,intent.getCode(),generation,target));
                                        });
                            });
                        })));
    }

    private Mono<WorkflowEntity> choose(UUID project, IssueTypeCreationIntent intent,
            List<ru.taska.api.project.v1.ProjectIssueTypeResponse> registry) {
        return workflows.findWorkflowForProject(properties.defaultProjectId(),"TASK")
                .filter(WorkflowEntity::isActive).switchIfEmpty(Mono.error(failed("WORKFLOW_NOT_AVAILABLE","Default workflow is unavailable")))
                .flatMap(fallback -> {
                    if (intent.hasWorkflowId()) {
                        UUID chosen=parse(intent.getWorkflowId());
                        if (chosen.equals(fallback.getId())) return Mono.just(fallback);
                        return workflows.findById(chosen).filter(WorkflowEntity::isActive)
                                .switchIfEmpty(Mono.error(failed("WORKFLOW_NOT_IN_PROJECT","Workflow is not owned by this project")))
                                .flatMap(workflow -> bindings.findByWorkflowId(chosen).collectList().map(existing -> {
                                    try { WorkflowOwnership.requireOwned(workflow,existing,project,properties.defaultProjectId()); }
                                    catch (DomainException wrong) { throw failed("WORKFLOW_NOT_IN_PROJECT","Workflow is not owned by this project"); }
                                    return workflow;
                                }));
                    }
                    var first=registry.stream().filter(type -> type.getCode().equals(intent.getFirstTypeCode())
                            && type.getGeneration().equals(intent.getFirstTypeGeneration())).findFirst()
                            .orElseThrow(() -> failed("ISSUE_TYPES_CHANGED","The default registry type changed"));
                    UUID firstGeneration=IssueTypeBindingIdentity.generation(first);
                    return workflows.findWorkflowForProjectGeneration(project,first.getCode(),firstGeneration,
                                    firstGeneration.equals(IssueTypeBindingIdentity.initialGeneration(project,first.getCode())))
                            .switchIfEmpty(Mono.just(fallback));
                });
    }

    private Mono<CreatedTypeBindingOutcome> bind(UUID project, UUID operation, String code, UUID generation, WorkflowEntity target) {
        boolean shared=properties.defaultProjectId().equals(target.getOwnerProjectId());
        return bindings.findByWorkflowId(target.getId()).collectList().flatMap(existing -> {
                        boolean fallback=shared || existing.stream().anyMatch(b -> b.getProjectId().equals(properties.defaultProjectId()));
                        if (!fallback) WorkflowOwnership.requireOwned(target,existing,project,properties.defaultProjectId());
                        Mono<Void> binding=fallback?database.sql("DELETE FROM taska.workflow_bindings WHERE project_id=:project AND issue_type=:type")
                                .bind("project",project).bind("type",code).fetch().rowsUpdated().then()
                                :database.sql("""
                                    INSERT INTO taska.workflow_bindings(project_id,issue_type,workflow_id,issue_type_generation)
                                    VALUES(:project,:type,:workflow,:generation)
                                    ON CONFLICT(project_id,issue_type) DO UPDATE SET workflow_id=EXCLUDED.workflow_id,
                                        issue_type_generation=EXCLUDED.issue_type_generation,updated_at=now()
                                    """).bind("project",project).bind("type",code).bind("workflow",target.getId()).bind("generation",generation)
                                    .fetch().rowsUpdated().then(workflows.incrementVersion(target.getId(), target.getVersion()).then());
                        return binding.then(database.sql("""
                                INSERT INTO taska.issue_type_creation_workflow_receipts(operation_id,project_id,state,workflow_id,code,generation)
                                VALUES(:operation,:project,'APPLIED',:workflow,:code,:generation)
                                """).bind("operation",operation).bind("project",project).bind("workflow",target.getId())
                                .bind("code",code).bind("generation",generation).fetch().rowsUpdated())
                                .then(receipt(project,operation));
        });
    }

    @Transactional
    public Mono<CreatedTypeBindingOutcome> cancel(UUID project, UUID operation) {
        return fence.lockProject(project).then(Mono.defer(() -> receipt(project,operation)))
                .switchIfEmpty(Mono.defer(() -> operations.creation(project,operation).flatMap(intent -> {
                    requireIdentity(intent,project,operation);
                    if (!List.of("CANCELLING","CANCELLED").contains(intent.getState()))
                        return Mono.error(failed("ISSUE_TYPE_CREATION_NOT_CANCELLING","A pending creation cannot be cancelled without its durable decision"));
                    return database.sql("INSERT INTO taska.issue_type_creation_workflow_receipts(operation_id,project_id,state) VALUES(:operation,:project,'CANCELLED')")
                            .bind("operation",operation).bind("project",project).fetch().rowsUpdated().then(receipt(project,operation));
                })));
    }
    private Mono<CreatedTypeBindingOutcome> receipt(UUID project, UUID operation) {
        return database.sql("SELECT state,workflow_id FROM taska.issue_type_creation_workflow_receipts WHERE project_id=:project AND operation_id=:operation")
                .bind("project",project).bind("operation",operation).map(row -> {
                    var result=CreatedTypeBindingOutcome.newBuilder().setOutcome(TypeOperationOutcome.newBuilder().setState(
                            row.get("state",String.class).equals("APPLIED")?TypeOperationState.TYPE_OPERATION_STATE_APPLIED:TypeOperationState.TYPE_OPERATION_STATE_CANCELLED));
                    UUID workflow=row.get("workflow_id",UUID.class);if (workflow!=null) result.setWorkflowId(workflow.toString());
                    return result.build();
                }).one();
    }
    private static void requireIdentity(IssueTypeCreationIntent intent, UUID project, UUID operation) {
        if (!intent.getProjectId().equals(project.toString()) || !intent.getOperationId().equals(operation.toString()))
            throw failed("ISSUE_TYPE_CREATION_IDENTITY_MISMATCH","Registry creation identity mismatch");
    }
    private static UUID parse(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid operation UUID");
        }
    }
    private static DomainException failed(String reason,String message) { return new DomainException(reason.equals("ISSUE_TYPES_CHANGED") ? DomainStatus.ABORTED : DomainStatus.INVALID_ARGUMENT,message,Map.of("reason",reason)); }
}
