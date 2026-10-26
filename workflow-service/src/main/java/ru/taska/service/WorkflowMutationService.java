package ru.taska.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.ProjectWorkflowAggregate;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.WorkflowMutation;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.*;
import ru.taska.service.WorkflowEditService.EditableWorkflow;
import ru.taska.transport.grpc.issue.WorkflowIssueMigrationClient;

@Service
@RequiredArgsConstructor
public class WorkflowMutationService {
    public enum Operation { UPDATE_WORKFLOW, UPDATE_STATUS, DELETE_STATUS, UPDATE_TRANSITION, DELETE_TRANSITION, DELETE_WORKFLOW }

    private final WorkflowEditService edits;
    private final WorkflowRepository workflows;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final WorkflowBindingRepository bindings;
    private final WorkflowIssueMigrationClient issues;
    private final WorkflowProperties properties;

    @Transactional
    public Mono<ProjectWorkflowAggregate> mutate(Operation operation, WorkflowMutation command) {
        return Mono.defer(() -> {
            if (command.version() != null && command.version() < 1) return invalid("version must be positive");
            return edits.loadEditableWorkflow(command.requestId(), command.nodeId(), command.actor(),
                            command.project(), command.workflow(), true)
                    .flatMap(current -> {
                        if (command.version() != null && command.version() != current.workflow().getVersion()) {
                            return Mono.error(new DomainException(DomainStatus.ABORTED, "Workflow version changed"));
                        }
                        return switch (operation) {
                            case UPDATE_WORKFLOW -> rename(current, command);
                            case UPDATE_STATUS -> updateStatus(current, command);
                            case DELETE_STATUS -> deleteStatus(current, command);
                            case UPDATE_TRANSITION -> updateTransition(current, command);
                            case DELETE_TRANSITION -> deleteTransition(current, command);
                            case DELETE_WORKFLOW -> deleteWorkflow(current, command);
                        };
                    });
        }).onErrorMap(DuplicateKeyException.class,
                e -> new DomainException(DomainStatus.ALREADY_EXISTS, "Workflow key, edge or sortOrder already exists"));
    }

    private Mono<ProjectWorkflowAggregate> rename(EditableWorkflow current, WorkflowMutation c) {
        if (c.name() == null) return invalid("name is required");
        var changed = copy(current, current.workflow().toBuilder().name(c.name().strip()).build(),
                current.statuses(), current.transitions());
        return validate(changed, c).then(Mono.defer(() -> finish(changed, c)));
    }

    private Mono<ProjectWorkflowAggregate> updateStatus(EditableWorkflow current, WorkflowMutation c) {
        if (c.name() == null && c.category() == null && c.sortOrder() == null) return invalid("At least one status field is required");
        return requireStatus(current, c.status()).flatMap(old -> {
            var changedStatus = old.toBuilder().name(c.name() == null ? old.getName() : c.name().strip())
                    .category(c.category() == null ? old.getCategory() : c.category())
                    .sortOrder(c.sortOrder() == null ? old.getSortOrder() : c.sortOrder()).build();
            var changed = copy(current, current.workflow(), replace(current.statuses(), old, changedStatus), current.transitions());
            return validate(changed, c).then(Mono.defer(() -> statuses.save(changedStatus)))
                    .then(Mono.defer(() -> finish(changed, c)));
        });
    }

    private Mono<ProjectWorkflowAggregate> updateTransition(EditableWorkflow current, WorkflowMutation c) {
        if (c.name() == null && c.hidden() == null && c.sortOrder() == null) return invalid("At least one transition field is required");
        return requireTransition(current, c.transition()).flatMap(old -> {
            var changedTransition = old.toBuilder().name(c.name() == null ? old.getName() : c.name().strip())
                    .hidden(c.hidden() == null ? old.isHidden() : c.hidden())
                    .sortOrder(c.sortOrder() == null ? old.getSortOrder() : c.sortOrder()).build();
            var changed = copy(current, current.workflow(), current.statuses(), replace(current.transitions(), old, changedTransition));
            return validate(changed, c).then(Mono.defer(() -> transitions.save(changedTransition)))
                    .then(Mono.defer(() -> finish(changed, c)));
        });
    }

    private Mono<ProjectWorkflowAggregate> deleteTransition(EditableWorkflow current, WorkflowMutation c) {
        return requireTransition(current, c.transition()).flatMap(old -> {
            var remaining = current.transitions().stream().filter(t -> !t.getId().equals(old.getId())).toList();
            var changed = copy(current, current.workflow(), current.statuses(), remaining);
            return validate(changed, c).then(Mono.defer(() -> transitions.delete(old)))
                    .then(Mono.defer(() -> finish(changed, c)));
        });
    }

    private Mono<ProjectWorkflowAggregate> deleteStatus(EditableWorkflow current, WorkflowMutation c) {
        return requireStatus(current, c.status()).flatMap(old -> {
            if ("TODO".equals(old.getStatusKey())) return precondition("The initial TODO status cannot be deleted");
            var remainingStatuses = current.statuses().stream().filter(s -> !s.getId().equals(old.getId())).toList();
            var remainingTransitions = current.transitions().stream()
                    .filter(t -> !t.getFromStatusId().equals(old.getId()) && !t.getToStatusId().equals(old.getId())).toList();
            var changed = copy(current, current.workflow(), remainingStatuses, remainingTransitions);
            List<String> validKeys = remainingStatuses.stream().map(StatusEntity::getStatusKey).toList();
            if (c.targetStatusKey() != null && !validKeys.contains(c.targetStatusKey())) return invalid("Target status is not in this workflow");
            Map<String, String> mapping = c.targetStatusKey() == null ? Map.of()
                    : Map.of(old.getStatusKey(), c.targetStatusKey());
            return validate(changed, c)
                    .then(Mono.defer(() -> issues.migrate(c, current.issueTypes(), validKeys, mapping)))
                    .thenMany(Flux.fromIterable(current.transitions()).filter(t -> !remainingTransitions.contains(t))
                            .concatMap(transitions::delete))
                    .then(Mono.defer(() -> statuses.delete(old)))
                    .then(Mono.defer(() -> finish(changed, c)));
        });
    }

    private Mono<ProjectWorkflowAggregate> deleteWorkflow(EditableWorkflow current, WorkflowMutation c) {
        // Every unbound project type uses the same general default workflow.
        return workflows.findWorkflowForProject(properties.defaultProjectId(), "TASK")
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Default workflow is missing")))
                .flatMap(fallback -> {
                    return Mono.zip(statuses.findActiveByWorkflowId(fallback.getId()).collectList(),
                                    transitions.findByWorkflowIdOrderBySortOrderAsc(fallback.getId()).collectList())
                            .flatMap(data -> {
                                List<String> validKeys = data.getT1().stream().map(StatusEntity::getStatusKey).toList();
                                if (!validKeys.contains("TODO") || data.getT2().stream().noneMatch(t -> !t.isHidden())) {
                                    return precondition("Default workflow is not usable");
                                }
                                Map<String, String> mapping = c.statusMappings() == null ? Map.of() : c.statusMappings();
                                List<String> sourceKeys = current.statuses().stream().map(StatusEntity::getStatusKey).toList();
                                // Issue DB commits first. Keep every moved status valid in the old graph
                                // too, so a failure before unlinking the workflow can be retried safely.
                                if (!sourceKeys.containsAll(mapping.keySet()) || !validKeys.containsAll(mapping.values())
                                        || !sourceKeys.containsAll(mapping.values())) {
                                    return invalid("Map to statuses present in both workflows; add a target status to the current workflow first if needed");
                                }
                                return issues.migrate(c, current.issueTypes(), validKeys, mapping)
                                        .thenMany(bindings.findByWorkflowId(c.workflow()).concatMap(bindings::delete))
                                        .then(workflows.delete(current.workflow()))
                                        .thenReturn(new ProjectWorkflowAggregate(new WorkflowAggregate(fallback,
                                                data.getT1(), data.getT2()), current.issueTypes(), true));
                            });
                });
    }

    private Mono<Void> validate(EditableWorkflow changed, WorkflowMutation c) {
        if (changed.statuses().isEmpty() || changed.statuses().stream().noneMatch(s -> "TODO".equals(s.getStatusKey()))
                || changed.transitions().stream().noneMatch(t -> !t.isHidden())) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Workflow requires a TODO status and at least one visible transition"));
        }
        return edits.checkViolations(edits.toCreationDto(c.project(), changed), c.requestId(), c.nodeId());
    }

    private Mono<ProjectWorkflowAggregate> finish(EditableWorkflow changed, WorkflowMutation c) {
        return workflows.updateMetadata(c.workflow(), changed.workflow().getName(), changed.workflow().getVersion())
                .flatMap(count -> count == 1 ? workflows.findById(c.workflow())
                        : Mono.error(new DomainException(DomainStatus.ABORTED, "Workflow was modified concurrently")))
                .flatMap(saved -> Mono.zip(statuses.findActiveByWorkflowId(c.workflow()).collectList(),
                                transitions.findByWorkflowIdOrderBySortOrderAsc(c.workflow()).collectList())
                        .map(data -> new ProjectWorkflowAggregate(new WorkflowAggregate(saved, data.getT1(), data.getT2()),
                                changed.issueTypes(), false)));
    }

    private Mono<StatusEntity> requireStatus(EditableWorkflow current, UUID id) {
        return Mono.justOrEmpty(current.statuses().stream().filter(s -> s.getId().equals(id)).findFirst())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Status not found in this workflow")));
    }

    private Mono<TransitionEntity> requireTransition(EditableWorkflow current, UUID id) {
        return Mono.justOrEmpty(current.transitions().stream().filter(t -> t.getId().equals(id)).findFirst())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Transition not found in this workflow")));
    }

    private EditableWorkflow copy(EditableWorkflow old, ru.taska.entity.WorkflowEntity workflow,
                                   List<StatusEntity> statusList, List<TransitionEntity> transitionList) {
        return new EditableWorkflow(workflow, old.issueTypes(), statusList, transitionList);
    }

    private <T> List<T> replace(List<T> values, T old, T replacement) {
        List<T> copy = new ArrayList<>(values);
        copy.set(copy.indexOf(old), replacement);
        return copy;
    }

    private <T> Mono<T> invalid(String message) { return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, message)); }
    private <T> Mono<T> precondition(String message) { return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, message)); }
}
