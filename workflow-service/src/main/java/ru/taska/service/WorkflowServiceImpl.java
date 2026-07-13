package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowServiceImpl implements WorkflowService {

    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowResolver workflowResolver;
    private final WorkflowCreateService workflowCreateService;
    private final ValidateTransitionService validateTransitionService;

    @Override
    public Mono<WorkflowAggregate> createWorkflow(String requestId, String nodeId, UUID actorUserId, WorkflowCreationDto dto) {
        return workflowCreateService.validateAndCreateWorkflow(requestId, nodeId, actorUserId, dto);
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<WorkflowAggregate> getWorkflow(UUID projectId, String issueType) {
        return workflowResolver.resolveWorkflow(projectId, issueType)
                .switchIfEmpty(Mono.error(
                        new DomainException(DomainStatus.NOT_FOUND, "Default Workflow not found")))
                .flatMap(workflow -> Mono.zip(
                        statusRepository.findActiveByWorkflowId(workflow.getId())
                                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                                        "No active statuses found for workflowId=" + workflow.getId())))
                                .collectList(),
                        transitionRepository.findVisibleByWorkflowId(workflow.getId())
                                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                                        "No visible transitions found for workflowId=" + workflow.getId())))
                                .collectList()
                ).map(t -> new WorkflowAggregate(workflow, t.getT1(), t.getT2())));
    }

    @Override
    public Mono<ValidateTransitionResponseDto> validateTransition(
            String requestId,
            String nodeId,
            UUID projectId,
            String issueType,
            UUID transitionId,
            String currentStatusKey,
            String payload,
            UUID actorUserId) {
        return validateTransitionService.validateTransition(requestId, nodeId, projectId, issueType,
                transitionId, currentStatusKey, payload, actorUserId);
    }
}
