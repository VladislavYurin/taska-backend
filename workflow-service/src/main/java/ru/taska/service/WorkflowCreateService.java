package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.dto.WorkflowCreationViolation;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowCreateService {

    private static final int INIT_VERSION = 1;

    private final WorkflowCreationValidator creationValidator;
    private final WorkflowCreationMapper mapper;
    private final WorkflowRepository workflowRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowBindingRepository bindingRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final WorkflowProperties workflowProperties;

    @Transactional
    public Mono<WorkflowAggregate> validateAndCreateWorkflow(String requestId, String nodeId, UUID actorUserId, WorkflowCreationDto dto) {
        return projectRoleChecker.checkProjectRole(
                        requestId,
                        nodeId,
                        dto.getProjectId(),
                        actorUserId,
                        workflowProperties.allowedRoles().createWorkflowRoles()
                )
                .then(Mono.fromCallable(() -> creationValidator.validateDto(dto)))
                .flatMap(violations -> handleViolations(violations, requestId, nodeId))
                .then(Mono.defer(() -> {
                    List<String> issueTypeNames = dto.getIssueTypes().stream().map(Enum::name).toList();
                    return bindingRepository.findByProjectIdAndIssueTypeIn(dto.getProjectId(), issueTypeNames).collectList();
                }))
                .flatMap(existingBindings -> checkNoExistingBindings(existingBindings, dto, requestId, nodeId))
                .then(Mono.defer(() -> createWorkflow(dto)
                        .doOnSuccess(aggregate ->
                                log.info("[{}][{}] Workflow successfully created: name={}", requestId, nodeId, dto.getName()))
                ));
    }

    private Mono<Void> checkNoExistingBindings(List<WorkflowBindingEntity> existingBindings, WorkflowCreationDto dto,
                                               String requestId, String nodeId) {
        if (existingBindings.isEmpty()) {
            return Mono.empty();
        }
        List<String> conflicting = existingBindings.stream()
                .map(WorkflowBindingEntity::getIssueType)
                .toList();
        String message = "Workflow binding already exists for project " + dto.getProjectId()
                + " and issue types: " + conflicting;
        log.warn("[{}][{}] {}", requestId, nodeId, message);
        return Mono.error(new DomainException(DomainStatus.ALREADY_EXISTS, message));
    }

    private Mono<WorkflowAggregate> createWorkflow(WorkflowCreationDto dto) {
        WorkflowEntity workflow = WorkflowEntity.builder()
                .name(dto.getName())
                .version(INIT_VERSION)
                .active(true)
                .build();

        return workflowRepository.save(workflow)
                .flatMap(savedWorkflow -> saveStatuses(savedWorkflow.getId(), dto)
                        .flatMap(savedStatuses -> {
                            Map<String, UUID> statusKeyToId = savedStatuses.stream()
                                    .collect(Collectors.toMap(StatusEntity::getStatusKey, StatusEntity::getId));
                            return saveTransitionsAndBindings(savedWorkflow.getId(), statusKeyToId, dto)
                                    .map(savedTransitions -> new WorkflowAggregate(savedWorkflow, savedStatuses, savedTransitions));
                        }));
    }

    private Mono<List<StatusEntity>> saveStatuses(UUID workflowId, WorkflowCreationDto dto) {
        List<StatusEntity> statuses = dto.getStatuses().stream()
                .map(s -> mapper.toStatusEntity(workflowId, s))
                .toList();

        return statusRepository.saveAll(statuses).collectList();
    }

    private Mono<List<TransitionEntity>> saveTransitionsAndBindings(UUID workflowId, Map<String, UUID> statusKeyToId,
                                                                    WorkflowCreationDto dto) {
        List<TransitionEntity> transitions = dto.getTransitions().stream()
                .map(t -> mapper.toTransitionEntity(workflowId, statusKeyToId, t))
                .toList();

        List<WorkflowBindingEntity> bindings = dto.getIssueTypes().stream()
                .map(issueType -> mapper.toBindingEntity(dto.getProjectId(), workflowId, issueType))
                .toList();

        return transitionRepository.saveAll(transitions).collectList()
                .flatMap(savedTransitions -> bindingRepository.saveAll(bindings).then()
                        .thenReturn(savedTransitions));
    }

    private Mono<Void> handleViolations(List<WorkflowCreationViolation> violations, String requestId, String nodeId) {
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        String message = creationValidator.buildViolationMessage(violations);
        log.warn("[{}][{}] {}", requestId, nodeId, message);
        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, message));
    }
}
