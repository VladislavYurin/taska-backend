package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.IssueType;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.dto.WorkflowCreationViolation;
import ru.taska.dto.WorkflowCreationViolationType;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Добавление статусов и переходов в workflow проекта.
 * <p>
 * Менять можно только workflow, привязанный к самому проекту. Default workflow привязан к default project
 * и общий для всех проектов без своего workflow, поэтому не меняется: проекту сначала нужен свой workflow.
 * Новый статус или переход проверяется вместе с уже сохранёнными теми же правилами, что и при создании workflow.
 * После изменения версия workflow увеличивается.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEditService {

    private static final String VIOLATION_MESSAGE_PREFIX = "Workflow update failed: ";

    // Нарушения, которые означают «такое уже есть», а не «запрос составлен неверно».
    private static final Set<WorkflowCreationViolationType> CONFLICT_VIOLATIONS = EnumSet.of(
            WorkflowCreationViolationType.DUPLICATE_STATUS_KEY,
            WorkflowCreationViolationType.DUPLICATE_TRANSITION
    );

    private final WorkflowCreationValidator validator;
    private final WorkflowCreationMapper mapper;
    private final WorkflowRepository workflowRepository;
    private final StatusRepository statusRepository;
    private final TransitionRepository transitionRepository;
    private final WorkflowBindingRepository bindingRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final WorkflowProperties workflowProperties;
    private final ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;
    private final WorkflowWriteFence writeFence;

    @Transactional
    public Mono<WorkflowAggregate> addStatus(String requestId, String nodeId, UUID actorUserId,
                                             UUID projectId, UUID workflowId, CreateWorkflowStatusDto status) {
        return loadEditableWorkflow(requestId, nodeId, actorUserId, projectId, workflowId)
                .flatMap(current -> {
                    WorkflowCreationDto updated = toCreationDto(projectId, current);
                    updated.getStatuses().add(status);
                    return checkViolations(updated, requestId, nodeId)
                            .then(Mono.defer(() -> statusRepository.save(mapper.toStatusEntity(workflowId, status))))
                            .then(bumpVersionAndLoad(current.workflow()));
                })
                .onErrorMap(DuplicateKeyException.class, e -> new DomainException(DomainStatus.ALREADY_EXISTS,
                        VIOLATION_MESSAGE_PREFIX + "Status key is not unique: " + status.getStatusKey()))
                .doOnSuccess(aggregate -> log.info("[{}][{}] Status added: workflowId={}, statusKey={}",
                        requestId, nodeId, workflowId, status.getStatusKey()));
    }

    @Transactional
    public Mono<WorkflowAggregate> addTransition(String requestId, String nodeId, UUID actorUserId,
                                                 UUID projectId, UUID workflowId, CreateWorkflowTransitionDto transition) {
        return loadEditableWorkflow(requestId, nodeId, actorUserId, projectId, workflowId)
                .flatMap(current -> {
                    WorkflowCreationDto updated = toCreationDto(projectId, current);
                    updated.getTransitions().add(transition);
                    Map<String, UUID> statusKeyToId = current.statuses().stream()
                            .collect(Collectors.toMap(StatusEntity::getStatusKey, StatusEntity::getId));
                    return checkViolations(updated, requestId, nodeId)
                            .then(Mono.defer(() -> transitionRepository.save(
                                    mapper.toTransitionEntity(workflowId, statusKeyToId, transition))))
                            .then(bumpVersionAndLoad(current.workflow()));
                })
                .onErrorMap(DuplicateKeyException.class, e -> new DomainException(DomainStatus.ALREADY_EXISTS,
                        VIOLATION_MESSAGE_PREFIX + "Duplicate transition from '" + transition.getFromStatusKey()
                                + "' to '" + transition.getToStatusKey() + "'"))
                .doOnSuccess(aggregate -> log.info("[{}][{}] Transition added: workflowId={}, from={}, to={}",
                        requestId, nodeId, workflowId, transition.getFromStatusKey(), transition.getToStatusKey()));
    }

    private Mono<EditableWorkflow> loadEditableWorkflow(String requestId, String nodeId, UUID actorUserId,
                                                        UUID projectId, UUID workflowId) {
        return loadEditableWorkflow(requestId, nodeId, actorUserId, projectId, workflowId, true);
    }

    Mono<EditableWorkflow> loadEditableWorkflow(String requestId, String nodeId, UUID actorUserId,
                                                UUID projectId, UUID workflowId, boolean lock) {
        return projectRoleChecker.checkProjectWriteRole(
                        requestId,
                        nodeId,
                        projectId,
                        actorUserId,
                        workflowProperties.allowedRoles().editWorkflowRoles()
                )
                .then(Mono.defer(() -> writeFence.lockAndCheck(projectId)))
                .then(Mono.defer(() -> projectClient.listIssueTypeDetails(requestId, nodeId, projectId, actorUserId)))
                .doOnNext(WorkflowWriteFence::requireWritableRegistry)
                .flatMap(types -> Mono.defer(() -> lock ? workflowRepository.findByIdForUpdate(workflowId) : workflowRepository.findById(workflowId))
                        .filter(WorkflowEntity::isActive)
                        .switchIfEmpty(Mono.error(() -> new DomainException(DomainStatus.NOT_FOUND,
                                "Workflow not found: id=" + workflowId)))
                        .flatMap(workflow -> bindingRepository.findByWorkflowId(workflowId).collectList()
                                .flatMap(bindings -> projectIssueTypes(bindings, projectId, workflow, types, requestId, nodeId))
                                .flatMap(issueTypes -> Mono.zip(
                                        statusRepository.findActiveByWorkflowId(workflowId).collectList(),
                                        transitionRepository.findByWorkflowIdOrderBySortOrderAsc(workflowId).collectList()
                                ).map(t -> new EditableWorkflow(workflow, issueTypes, t.getT1(), t.getT2())))));
    }

    private Mono<List<IssueType>> projectIssueTypes(List<WorkflowBindingEntity> bindings, UUID projectId,
            WorkflowEntity workflow, List<ru.taska.api.project.v1.ProjectIssueTypeResponse> types, String requestId, String nodeId) {
        return Mono.fromCallable(() -> {
            WorkflowOwnership.requireOwned(workflow, bindings, projectId, workflowProperties.defaultProjectId());
            return types.stream().filter(type -> bindings.stream().anyMatch(binding -> binding.getProjectId().equals(projectId)
                            && IssueTypeBindingIdentity.matches(binding,type)))
                    .map(type -> IssueType.valueOf(type.getCode())).toList();
        });
    }

    WorkflowCreationDto toCreationDto(UUID projectId, EditableWorkflow current) {
        Map<UUID, String> statusIdToKey = current.statuses().stream()
                .collect(Collectors.toMap(StatusEntity::getId, StatusEntity::getStatusKey));
        return WorkflowCreationDto.builder()
                .projectId(projectId)
                .name(current.workflow().getName())
                .issueTypes(current.issueTypes())
                .statuses(new ArrayList<>(current.statuses().stream().map(mapper::toStatusDto).toList()))
                .transitions(new ArrayList<>(current.transitions().stream()
                        .map(t -> mapper.toTransitionDto(t, statusIdToKey))
                        .toList()))
                .build();
    }

    Mono<Void> checkViolations(WorkflowCreationDto updated, String requestId, String nodeId) {
        List<WorkflowCreationViolation> violations = validator.validateDto(updated).stream()
                .filter(v -> v.type()!=WorkflowCreationViolationType.ISSUE_TYPE_LIST_EMPTY).toList();
        if (violations.isEmpty()) {
            return Mono.empty();
        }
        String message = validator.buildViolationMessage(VIOLATION_MESSAGE_PREFIX, violations);
        log.warn("[{}][{}] {}", requestId, nodeId, message);
        DomainStatus status = violations.stream().allMatch(v -> CONFLICT_VIOLATIONS.contains(v.type()))
                ? DomainStatus.ALREADY_EXISTS
                : DomainStatus.INVALID_ARGUMENT;
        return Mono.error(new DomainException(status, message));
    }

    // Возвращает workflow так же, как его читает GetWorkflowForProject: все статусы и видимые переходы.
    private Mono<WorkflowAggregate> bumpVersionAndLoad(WorkflowEntity workflow) {
        return Mono.defer(() -> workflowRepository.incrementVersion(workflow.getId(), workflow.getVersion()))
                .flatMap(rows -> rows == 0
                        ? Mono.error(new DomainException(
                        DomainStatus.ABORTED,
                        "Workflow was modified concurrently"))
                        : Mono.defer(() -> workflowRepository.findById(workflow.getId())))
                .flatMap(saved -> Mono.zip(
                        statusRepository.findActiveByWorkflowId(saved.getId()).collectList(),
                        transitionRepository.findVisibleByWorkflowId(saved.getId()).collectList()
                ).map(t -> new WorkflowAggregate(saved, t.getT1(), t.getT2())));
    }

    record EditableWorkflow(
            WorkflowEntity workflow,
            List<IssueType> issueTypes,
            List<StatusEntity> statuses,
            List<TransitionEntity> transitions
    ) {
    }
}
