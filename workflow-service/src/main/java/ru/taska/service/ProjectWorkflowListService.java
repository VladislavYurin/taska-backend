package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectWorkflowAggregate;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Все workflow, по которым работает проект, со всеми статусами и всеми переходами, включая скрытые.
 * <p>
 * Workflow для типа задачи выбирается так же, как в {@link WorkflowResolver}: активный workflow проекта,
 * иначе активный default workflow. Типы задач с одним и тем же workflow собираются в одну запись.
 */
@Service
@RequiredArgsConstructor
public class ProjectWorkflowListService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowBindingRepository bindingRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowProperties workflowProperties;

    @Transactional(readOnly = true)
    public Mono<List<ProjectWorkflowAggregate>> listProjectWorkflows(UUID projectId) {
        UUID defaultProjectId = workflowProperties.defaultProjectId();
        return bindingRepository.findByProjectIdIn(List.of(projectId, defaultProjectId)).collectList()
                .flatMap(bindings -> workflowRepository
                        .findAllById(bindings.stream().map(WorkflowBindingEntity::getWorkflowId).distinct().toList())
                        .filter(WorkflowEntity::isActive)
                        .collectMap(WorkflowEntity::getId)
                        .flatMap(activeWorkflows -> {
                            Map<UUID, List<IssueType>> issueTypesByWorkflow = new LinkedHashMap<>();
                            for (IssueType issueType : IssueType.values()) {
                                findBoundWorkflow(bindings, activeWorkflows, projectId, issueType)
                                        .or(() -> findBoundWorkflow(bindings, activeWorkflows, defaultProjectId, issueType))
                                        .ifPresent(workflowId -> issueTypesByWorkflow
                                                .computeIfAbsent(workflowId, id -> new ArrayList<>())
                                                .add(issueType));
                            }
                            return Flux.fromIterable(issueTypesByWorkflow.entrySet())
                                    .concatMap(entry -> loadWithAllTransitions(activeWorkflows.get(entry.getKey()))
                                            .map(aggregate -> new ProjectWorkflowAggregate(
                                                    aggregate,
                                                    entry.getValue(),
                                                    isBoundTo(bindings, entry.getKey(), defaultProjectId))))
                                    .collectList();
                        }));
    }

    private Optional<UUID> findBoundWorkflow(List<WorkflowBindingEntity> bindings, Map<UUID, WorkflowEntity> activeWorkflows,
                                             UUID projectId, IssueType issueType) {
        return bindings.stream()
                .filter(b -> b.getProjectId().equals(projectId)
                        && b.getIssueType().equals(issueType.name())
                        && activeWorkflows.containsKey(b.getWorkflowId()))
                .map(WorkflowBindingEntity::getWorkflowId)
                .findFirst();
    }

    private boolean isBoundTo(List<WorkflowBindingEntity> bindings, UUID workflowId, UUID projectId) {
        return bindings.stream()
                .anyMatch(b -> b.getWorkflowId().equals(workflowId) && b.getProjectId().equals(projectId));
    }

    private Mono<WorkflowAggregate> loadWithAllTransitions(WorkflowEntity workflow) {
        return Mono.zip(
                statusRepository.findActiveByWorkflowId(workflow.getId()).collectList(),
                transitionRepository.findByWorkflowIdOrderBySortOrderAsc(workflow.getId()).collectList()
        ).map(t -> new WorkflowAggregate(workflow, t.getT1(), t.getT2()));
    }
}
