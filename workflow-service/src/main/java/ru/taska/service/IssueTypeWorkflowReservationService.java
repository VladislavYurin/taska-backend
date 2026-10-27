package ru.taska.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.registry.v1.IssueTypeDeletionIntent;
import ru.taska.api.registry.v1.IssueTypeMoveWorkflowSnapshot;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.IssueTypeDeletionJournalClient;

/** Durable graph snapshot; no TTL or actor permission check on terminal cleanup. */
@Service
@RequiredArgsConstructor
public class IssueTypeWorkflowReservationService {
    private final WorkflowWriteFence fence;
    private final IssueTypeDeletionJournalClient journal;
    private final GrpcProjectServiceClient projects;
    private final WorkflowRepository workflows;
    private final StatusRepository statuses;
    private final WorkflowProperties properties;
    private final DatabaseClient database;

    @Transactional
    public Mono<IssueTypeMoveWorkflowSnapshot> reserve(UUID project, UUID operation) {
        return fence.lockProject(project).then(Mono.defer(() -> fence.requireNoBindingChange(project)))
                .then(Mono.defer(() -> journal.get(project, operation)))
                .flatMap(intent -> {
                    requireIdentity(intent, project, operation);
                    if (!"PENDING".equals(intent.getState()) || !intent.hasTargetCode() || !intent.hasTargetGeneration())
                        return Mono.error(failed("ISSUE_TYPE_OPERATION_NOT_PENDING", "Only a pending move can reserve a workflow"));
                    return projects.getIssueTypesInternal(project).flatMap(types -> {
                        ProjectIssueTypeResponse source = requireType(types, intent.getSourceCode(), intent.getSourceGeneration(), operation);
                        ProjectIssueTypeResponse target = requireType(types, intent.getTargetCode(), intent.getTargetGeneration(), operation);
                        if (source.getCode().equals(target.getCode())) return Mono.error(failed("INVALID_TARGET_ISSUE_TYPE", "Source and target must differ"));
                        return pin(project, operation).flatMap(saved -> saved.released()
                                        ? Mono.<IssueTypeMoveWorkflowSnapshot>error(failed("ISSUE_TYPE_OPERATION_FINISHED", "Workflow reservation was already released"))
                                        : Mono.just(saved.snapshot()))
                                .switchIfEmpty(Mono.defer(() -> create(target, project, operation)));
                    });
                });
    }

    private Mono<IssueTypeMoveWorkflowSnapshot> create(ProjectIssueTypeResponse target, UUID project, UUID operation) {
        return database.sql("SELECT EXISTS (SELECT 1 FROM taska.issue_type_move_workflow_pins WHERE project_id=:project AND released=false)")
                .bind("project", project).map(row -> row.get(0, Boolean.class)).one()
                .flatMap(active -> {
                    if (active) return Mono.<WorkflowEntity>error(WorkflowWriteFence.pending());
                    UUID generation = IssueTypeBindingIdentity.generation(target);
                    return workflows.findWorkflowForProjectGeneration(project, target.getCode(), generation,
                                    generation.equals(IssueTypeBindingIdentity.initialGeneration(project, target.getCode())))
                            .switchIfEmpty(workflows.findWorkflowForProject(properties.defaultProjectId(), "TASK"))
                            .switchIfEmpty(Mono.error(failed("WORKFLOW_NOT_FOUND", "Target workflow is unavailable")));
                })
                // Drain older graph mutations before taking the persisted snapshot. New writers hold 418.
                .flatMap(workflow -> workflows.findByIdForUpdate(workflow.getId()))
                .filter(WorkflowEntity::isActive)
                .switchIfEmpty(Mono.error(failed("WORKFLOW_NOT_FOUND", "Target workflow is unavailable")))
                .flatMap(workflow -> statuses.findActiveByWorkflowId(workflow.getId()).map(status -> status.getStatusKey())
                        .collectList().flatMap(keys -> {
                            if (keys.isEmpty()) return Mono.error(failed("WORKFLOW_NOT_FOUND", "Target workflow has no statuses"));
                            return database.sql("INSERT INTO taska.issue_type_move_workflow_pins(operation_id,project_id,workflow_id,valid_status_keys) VALUES(:operation,:project,:workflow,:keys)")
                                    .bind("operation", operation).bind("project", project).bind("workflow", workflow.getId())
                                    .bind("keys", keys.toArray(String[]::new)).then()
                                    .thenReturn(snapshot(project, operation, workflow.getId(), keys));
                        }));
    }

    public Mono<IssueTypeMoveWorkflowSnapshot> get(UUID project, UUID operation) {
        return pin(project, operation).filter(saved -> !saved.released()).map(Pin::snapshot)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Active workflow reservation not found")));
    }

    @Transactional
    public Mono<Void> release(UUID project, UUID operation) {
        return fence.lockProject(project).then(Mono.defer(() -> journal.get(project, operation)))
                .flatMap(intent -> {
                    requireIdentity(intent, project, operation);
                    if (!List.of("COMPLETED", "CANCELLED").contains(intent.getState()))
                        return Mono.error(failed("ISSUE_TYPE_OPERATION_NOT_TERMINAL", "A pending operation cannot release its workflow"));
                    return database.sql("UPDATE taska.issue_type_move_workflow_pins SET released=true WHERE project_id=:project AND operation_id=:operation AND released=false")
                            .bind("project", project).bind("operation", operation).then();
                });
    }

    private Mono<Pin> pin(UUID project, UUID operation) {
        return database.sql("SELECT workflow_id,valid_status_keys,released FROM taska.issue_type_move_workflow_pins WHERE project_id=:project AND operation_id=:operation")
                .bind("project", project).bind("operation", operation)
                .map(row -> new Pin(snapshot(project, operation, row.get("workflow_id", UUID.class),
                        Arrays.asList(row.get("valid_status_keys", String[].class))), Boolean.TRUE.equals(row.get("released", Boolean.class))))
                .one();
    }

    private static ProjectIssueTypeResponse requireType(List<ProjectIssueTypeResponse> types, String code, String generation, UUID operation) {
        return types.stream().filter(type -> type.getCode().equals(code)).findFirst()
                .filter(type -> type.getGeneration().equals(generation) && type.getPendingOperationId().equals(operation.toString()))
                .orElseThrow(() -> failed("ISSUE_TYPES_CHANGED", "The reserved issue-type generation changed"));
    }
    private static void requireIdentity(IssueTypeDeletionIntent intent, UUID project, UUID operation) {
        if (!intent.getProjectId().equals(project.toString()) || !intent.getOperationId().equals(operation.toString()))
            throw failed("INVALID_ISSUE_TYPE_OPERATION", "Operation identity does not match its journal");
    }
    private static IssueTypeMoveWorkflowSnapshot snapshot(UUID project, UUID operation, UUID workflow, List<String> keys) {
        return IssueTypeMoveWorkflowSnapshot.newBuilder().setProjectId(project.toString()).setOperationId(operation.toString())
                .setWorkflowId(workflow.toString()).addAllValidStatusKeys(keys).build();
    }
    private record Pin(IssueTypeMoveWorkflowSnapshot snapshot, boolean released) { }
    private static DomainException failed(String reason, String message) {
        return new DomainException(DomainStatus.FAILED_PRECONDITION, message, Map.of("reason", reason));
    }
}
