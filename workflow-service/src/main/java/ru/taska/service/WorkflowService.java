package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectWorkflowAggregate;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.dto.WorkflowCreationDto;

import java.util.List;
import java.util.UUID;

public interface WorkflowService {

    Mono<WorkflowAggregate> getWorkflow(UUID projectId, String issueType);

    Mono<ValidateTransitionResponseDto> validateTransition(
            String requestId,
            String nodeId,
            UUID projectId,
            String issueType,
            UUID transitionId,
            String currentStatusKey,
            String payload,
            UUID actorUserId
    );

    Mono<WorkflowAggregate> createWorkflow(String requestId, String nodeId, UUID actorUserId, WorkflowCreationDto dto);

    Mono<WorkflowAggregate> addStatus(String requestId, String nodeId, UUID actorUserId,
                                      UUID projectId, UUID workflowId, CreateWorkflowStatusDto status);

    Mono<WorkflowAggregate> addTransition(String requestId, String nodeId, UUID actorUserId,
                                          UUID projectId, UUID workflowId, CreateWorkflowTransitionDto transition);

    Mono<List<ProjectWorkflowAggregate>> listProjectWorkflows(UUID projectId);
}
