package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.ValidateTransitionResponseDto;

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
}