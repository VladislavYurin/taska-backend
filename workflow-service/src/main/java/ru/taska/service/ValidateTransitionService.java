package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.dto.TransitionViolation;
import ru.taska.dto.TransitionViolationDto;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ValidateTransitionService {

    private static final String ISSUE_STATUS_KEY_UNSPECIFIED = "ISSUE_STATUS_UNSPECIFIED";

    private final WorkflowResolver workflowResolver;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;

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

        // Получаем workflow (с fallback на default project)
        Mono<Optional<WorkflowEntity>> workflowMono = workflowResolver.resolveWorkflow(projectId, issueType)
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
                                TransitionViolation.WORKFLOW_NOT_FOUND,
                                String.format("Workflow not found for projectId=%s, issueType=%s", projectId, issueType)
                        ));
                    }

                    // Проверка наличия transition
                    if (transitionOpt.isEmpty()) {
                        violations.add(new TransitionViolationDto(
                                TransitionViolation.TRANSITION_NOT_FOUND,
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

                    // Проверяем, что transition принадлежит этому workflow.
                    // Если это не так возвращаем нарушения без дальнейшей валидации
                    if (!transition.getWorkflowId().equals(workflow.getId())) {
                        violations.add(new TransitionViolationDto(
                                TransitionViolation.TRANSITION_DOESNT_BELONG_TO_WORKFLOW,
                                String.format("Transition %s does not belong to workflow %s",
                                        transitionId, workflow.getId())
                        ));
                        return Mono.just(buildFailedResponse(violations));
                    }

                    // Проверяем текущий статус
                    return statusRepository.findByWorkflowIdAndStatusKey(workflow.getId(), currentStatusKey)
                            .flatMap(currentStatus -> {
                                // Проверяем, что найденный статус соответствует fromStatusId перехода
                                if (!currentStatus.getId().equals(transition.getFromStatusId())) {
                                    violations.add(new TransitionViolationDto(
                                            TransitionViolation.FROM_STATUS_DOESNT_MATCH,
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
                                            return Mono.just(buildSuccessResponse(toStatus.getStatusKey()));
                                        })
                                        .switchIfEmpty(Mono.defer(() -> {
                                            // Целевой статус не найден
                                            violations.add(new TransitionViolationDto(
                                                    TransitionViolation.TARGET_STATUS_NOT_FOUND,
                                                    String.format("Target status (id=%s) not found in workflow %s",
                                                            transition.getToStatusId(), workflow.getId())
                                            ));
                                            return Mono.just(buildFailedResponse(violations));
                                        }));
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // Текущий статус не найден
                                violations.add(new TransitionViolationDto(
                                        TransitionViolation.CURRENT_STATUS_NOT_FOUND,
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
                .valid(false)
                .toStatusKey(ISSUE_STATUS_KEY_UNSPECIFIED)
                .violations(violations)
                .build();
    }

    private ValidateTransitionResponseDto buildSuccessResponse(String toStatusKey) {
        return ValidateTransitionResponseDto.builder()
                .valid(true)
                .toStatusKey(toStatusKey)
                .violations(new ArrayList<>())
                .build();
    }
}
