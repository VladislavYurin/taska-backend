package ru.taska.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowProperties workflowProperties;
    private final IssueMapper issueMapper;

    @Override
    public Mono<WorkflowAggregate> getWorkflow(UUID projectId, String issueType) {
        if(!issueMapper.isValidType(issueType)){
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                                        "IssueType = " + issueType + "is wrong"));
        }
        return workflowRepository.findWorkflowForProject(projectId, issueType)
                .switchIfEmpty(
                        workflowRepository.findWorkflowForProject(workflowProperties.defaultProjectId(), issueType)
                                .switchIfEmpty(Mono.error(
                                        new DomainException(DomainStatus.NOT_FOUND,
                                        "Default Workflow not found")))
                )
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