package ru.taska.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.api.workflow.v1.IssueStatus;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.dto.TransitionViolationDto;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.dto.Violation;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.StatusMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowProperties workflowProperties;
    private final IssueMapper issueMapper;
    private final StatusMapper statusMapper;

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

    @Override
    @Transactional(readOnly = true)
    public Mono<ValidateTransitionResponseDto> validateTransition(
            String requestId,
            String nodeId,
            UUID projectId,
            String issueType,
            UUID transitionId,
            String currentStatusKey,
            String payload,
            UUID actorUserId) {

        log.debug("[{}][{}] Validating transition: projectId={}, issueType={}, transitionId={}, currentStatusKey={}",
                requestId, nodeId, projectId, issueType, transitionId, currentStatusKey);

        // Получаем workflow
        Mono<Optional<WorkflowEntity>> workflowMono = workflowRepository
                .findWorkflowForProject(projectId, issueType)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());

        // Получаем transition
        Mono<Optional<TransitionEntity>> transitionMono = transitionRepository
                .findById(transitionId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());

        return Mono.zip(workflowMono, transitionMono)
                .flatMap(tuple -> {
                    List<TransitionViolationDto> violations = new ArrayList<>();

                    Optional<WorkflowEntity> workflowOpt = tuple.getT1();
                    Optional<TransitionEntity> transitionOpt = tuple.getT2();

                    // Проверка наличия workflow
                    if (workflowOpt.isEmpty()) {
                        violations.add(new TransitionViolationDto(
                                Violation.WORKFLOW_NOT_FOUND,
                                String.format("Workflow not found for projectId=%s, issueType=%s", projectId, issueType)
                        ));
                    }

                    // Проверка наличия transition
                    if (transitionOpt.isEmpty()) {
                        violations.add(new TransitionViolationDto(
                                Violation.TRANSITION_NOT_FOUND,
                                String.format("Transition not found: id=%s", transitionId)
                        ));
                        return Mono.just(buildFailedResponse(violations));
                    }

                    // Если workflow не найден, но transition найден, возвращаем нарушения
                    if (workflowOpt.isEmpty()) {
                        return Mono.just(buildFailedResponse(violations));
                    }

                    WorkflowEntity workflow = workflowOpt.get();
                    TransitionEntity transition = transitionOpt.get();

                    // Проверяем, что transition принадлежит этому workflow
                    if (!transition.getWorkflowId().equals(workflow.getId())) {
                        violations.add(new TransitionViolationDto(
                                Violation.TRANSITION_DOESNT_BELONG_TO_WORKFLOW,
                                String.format("Transition %s does not belong to workflow %s",
                                        transitionId, workflow.getId())
                        ));
                    }

                    // Проверяем текущий статус
                    return statusRepository.findByWorkflowIdAndStatusKey(workflow.getId(), currentStatusKey)
                            .flatMap(currentStatus -> {
                                // Проверяем, что найденный статус соответствует fromStatusId перехода
                                if (!currentStatus.getId().equals(transition.getFromStatusId())) {
                                    violations.add(new TransitionViolationDto(
                                            Violation.FROM_STATUS_DOESNT_MATCH,
                                            String.format("Current issue status '%s' does not match transition's fromStatus",
                                                    currentStatusKey)
                                    ));
                                }

                                // Проверяем целевой статус
                                return statusRepository.findById(transition.getToStatusId())
                                        .flatMap(toStatus -> {
                                            // Если есть нарушения - возвращаем ответ с нарушениями
                                            if (!violations.isEmpty()) {
                                                return Mono.just(buildFailedResponse(violations));
                                            }
                                            return Mono.just(buildSuccessResponse(transition, toStatus.getStatusKey()));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> {
                                            // Целевой статус не найден
                                            violations.add(new TransitionViolationDto(
                                                    Violation.TARGET_STATUS_NOT_FOUND,
                                                    String.format("Target status (id=%s) not found in workflow %s",
                                                            transition.getToStatusId(), workflow.getId())
                                            ));
                                            return Mono.just(buildFailedResponse(violations));
                                        }));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // Текущий статус не найден
                                violations.add(new TransitionViolationDto(
                                        Violation.CURRENT_STATUS_NOT_FOUND,
                                        String.format("Current status '%s' not found in workflow %s",
                                                currentStatusKey, workflow.getId())
                                ));
                                return Mono.just(buildFailedResponse(violations));
                            }));
                })
                .doOnSuccess(response -> {
                    assert response != null;
                    log.info("[{}][{}] Transition validation completed: transitionId={}, valid={}, violations={}",
                            requestId, nodeId, transitionId, response.isValid(),
                            response.getViolations() != null ? response.getViolations().size() : 0);
                })
                .doOnError(ex -> {
                    log.error("[{}][{}] Error during transition validation: {}", requestId, nodeId, ex.getMessage(), ex);
                });
    }

    private ValidateTransitionResponseDto buildFailedResponse(List<TransitionViolationDto> violations) {
        return ValidateTransitionResponseDto.builder()
                .valid(violations.isEmpty())
                .toStatusKey(IssueStatus.ISSUE_STATUS_UNSPECIFIED)
                .violations(violations)
                .build();
    }

    private ValidateTransitionResponseDto buildSuccessResponse(TransitionEntity transition, String toStatusKey) {
        IssueStatus issueStatus = statusMapper.toProtoStatus(toStatusKey);
        return ValidateTransitionResponseDto.builder()
                .valid(true)
                .toStatusKey(issueStatus)
                .violations(new ArrayList<>())
                .build();
    }
}