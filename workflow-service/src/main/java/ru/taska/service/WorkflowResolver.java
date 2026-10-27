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
    private final ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;
    private final WorkflowWriteFence fence;

    public Mono<WorkflowEntity> resolveWorkflow(UUID projectId, String issueType) {
        if (projectId.equals(workflowProperties.defaultProjectId())) {
            return workflowRepository.findWorkflowForProject(projectId, issueType);
        }
        return fence.requireReadable(projectId).then(Mono.defer(() -> projectClient.getIssueTypesInternal(projectId)))
                .flatMap(types -> reactor.core.publisher.Flux.fromIterable(types)
                        .filter(type -> type.getCode().equals(issueType)).next()
                        .flatMap(type -> {
                            UUID generation = IssueTypeBindingIdentity.generation(type);
                            return workflowRepository.findWorkflowForProjectGeneration(projectId, issueType, generation,
                                    generation.equals(IssueTypeBindingIdentity.initialGeneration(projectId, issueType)))
                                    .switchIfEmpty(workflowRepository.findWorkflowForProject(workflowProperties.defaultProjectId(), "TASK"));
                        }));
    }
}
