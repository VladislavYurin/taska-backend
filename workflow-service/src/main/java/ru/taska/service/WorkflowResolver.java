package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.entity.WorkflowEntity;
import ru.taska.repository.WorkflowRepository;

import java.util.UUID;

/**
 * Разрешает workflow для проекта с fallback на default project.
 * Возвращает пустой Mono, если workflow не найден ни для проекта, ни по умолчанию.
 */
@Service
@RequiredArgsConstructor
public class WorkflowResolver {

    private final WorkflowRepository workflowRepository;
    private final WorkflowProperties workflowProperties;

    public Mono<WorkflowEntity> resolveWorkflow(UUID projectId, String issueType) {
        return workflowRepository.findWorkflowForProject(projectId, issueType)
                .switchIfEmpty(
                        workflowRepository.findWorkflowForProject(workflowProperties.defaultProjectId(), issueType)
                );
    }
}
