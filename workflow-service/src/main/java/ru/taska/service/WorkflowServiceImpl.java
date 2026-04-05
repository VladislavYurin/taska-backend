package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowRepository;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;

    @Override
    public Mono<WorkflowAggregate> getWorkflow(UUID projectId, String issueType) {
        return workflowRepository.findWorkflowForProject(projectId, issueType)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                        "Workflow not found for projectId=" + projectId + ", issueType=" + issueType)))
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
}