package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.IdempotencyKey;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.PageResult;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.labels.ProjectLabels;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IdempotencyKeyRepository;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.ProjectCounterRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.IssueService;
import ru.taska.service.OutboxEventService;
import ru.taska.service.watcher.IssueAutoWatchService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.util.PayloadSerializer;
import ru.taska.util.RequestHasher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    private static final int INIT_VERSION = 1;
    private static final String INIT_STATUS = "TODO";

    private final IssueProperties issueProperties;
    private final GrpcProjectServiceClient grpcProjectServiceClient;
    private final ProjectCounterRepository projectCounterRepository;
    private final IssueRepository issueRepository;
    private final IssueHistoryService issueHistoryService;
    private final PayloadSerializer payloadSerializer;
    private final OutboxEventService outboxEventService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IssueMapper issueMapper;
    private final ProjectRoleChecker projectRoleChecker;
    private final ObjectMapper objectMapper;
    private final IssueHistoryRepository issueHistoryRepository;
    private final IssueAutoWatchService issueAutoWatchService;
    private final IssueLabelsRepository issueLabelsRepository;

    @Override
    @Transactional
    public Mono<Issue> createIssue(
            String requestId,
            String nodeId,
            String idempotencyKey,
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    ) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().createIssueRoles();
        String currentRequestHash = RequestHasher.hashIssueCreateRequest(projectId, issueType, summary, description, priority, reporterId);

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, reporterId, allowedRoles)
                .then(idempotencyKeyRepository.findByUserIdAndKey(reporterId, idempotencyKey)
                        .flatMap(keyEntity -> {

                            if (!keyEntity.getRequestHash().equals(currentRequestHash)) {
                                log.warn("[{}][{}] Idempotency Key [{}] already used with a different request body", requestId, nodeId, idempotencyKey);
                                return Mono.error(new DomainException(
                                        DomainStatus.FAILED_PRECONDITION,
                                        "Idempotency Key already used with a different request body"
                                ));
                            }

                            return Mono.fromCallable(() ->
                                            objectMapper.treeToValue(keyEntity.getResponse(), Issue.class))
                                    .onErrorMap(JacksonException.class, e -> new DomainException(DomainStatus.INTERNAL, "Corrupted idempotency response"));

                        }))
                .switchIfEmpty(grpcProjectServiceClient.getProjectKeyInternal(requestId, nodeId, projectId)
                        .flatMap(projectKey -> projectCounterRepository.getNextIssueNumberAndIncrement(projectId)
                                .flatMap(number -> {
                                    return issueRepository.save(Issue.builder()
                                            .projectId(projectId)
                                            .issueNumber(number)
                                            .issueKey(projectKey + "-" + number)
                                            .issueType(issueType)
                                            .summary(summary)
                                            .description(Objects.requireNonNullElse(description, ""))
                                            .priority(priority)
                                            .reporterId(reporterId)
                                            .statusKey(INIT_STATUS)
                                            .version(INIT_VERSION)
                                            .build());
                                })
                                .flatMap(issue -> {
                                    IdempotencyKey keyEntity = issueMapper.buildIdempotencyKey(idempotencyKey, reporterId, currentRequestHash,
                                            issue, issueProperties.idempotencyKeyTtl().ttl());
                                    return issueHistoryService.saveIssueCreateHistory(requestId, nodeId, issue)
                                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE, issue))
                                            .then(idempotencyKeyRepository.save(keyEntity))
                                            .then(issueAutoWatchService.watchReporterOnCreate(requestId, nodeId, issue))
                                            .thenReturn(issue)
                                            .doOnSuccess(savedIssue ->
                                                    log.info("[{}][{}] Issue successfully created by user with id: {}",
                                                            requestId, nodeId, reporterId));
                                }))
                );
    }

    @Override
    @Transactional
    public Mono<Issue> assignIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID assigneeId,
            UUID actorUserId
    ) {
        return issueRepository.findActiveByIdForUpdate(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);

                    return Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " not found"
                    ));
                }))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().assignIssueRoles();

                    Mono<Void> actorCheck = projectRoleChecker.checkProjectRole(
                            requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles
                    );

                    Mono<Void> assigneeCheck = actorUserId.equals(assigneeId)
                            ? Mono.empty()
                            : projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), assigneeId, allowedRoles);

                    return actorCheck
                            .then(assigneeCheck)
                            .thenReturn(issue);
                })
                .flatMap(assignedIssue -> {
                    if (isAssigned(assignedIssue, assigneeId)) {
                        return Mono.just(assignedIssue);
                    }

                    JsonNode payload = payloadSerializer.createIssueAssignedPayload(assignedIssue.getAssigneeId(), assigneeId);
                    assignedIssue.setAssigneeId(assigneeId);

                    return issueRepository.save(assignedIssue)
                            .flatMap(savedIssue -> {
                                return issueHistoryService.saveIssueHistory(
                                                requestId, nodeId, assignedIssue.getId(), actorUserId, IssueEventType.ASSIGNED, payload)
                                        .then(outboxEventService.saveOutboxEvent(
                                                requestId, nodeId, AggregateType.ISSUE, assignedIssue.getId(), EventType.ISSUE_ASSIGNED, payload))
                                        .then(issueAutoWatchService.watchAssigneeOnAssign(
                                                requestId, nodeId, savedIssue, actorUserId))
                                        .then(Mono.fromRunnable(() ->
                                                log.debug("[{}][{}] User with id: {} successfully assigned to issue with id: {}",
                                                        requestId, nodeId, assigneeId, issueId)))
                                        .thenReturn(savedIssue);
                            });
                });
    }

    @Override
    @Transactional
    public Mono<Issue> updateIssue(String requestId, String nodeId, UUID issueId, UUID actorUserId,
                                   String summary, String description, IssuePriority priority) {
        return issueRepository.findActiveByIdForUpdate(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}]Issue with id: {} was not found", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " was not found"));
                }))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().updateIssueRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(issue);
                })
                .flatMap(updatingIssue -> {
                    JsonNode payload = payloadSerializer.createIssueUpdatedPayload(updatingIssue, actorUserId, summary, description, priority);
                    if (payload.isEmpty()) {
                        log.info("[{}][{}] Issue with id: {} equals updated updatingIssue by user with id: {}",
                                requestId, nodeId, issueId, actorUserId);
                        return Mono.just(updatingIssue);
                    }

                    updatingIssue.setSummary(summary);
                    updatingIssue.setDescription(description);
                    updatingIssue.setPriority(priority);
                    updatingIssue.setUpdatedAt(Instant.now());
                    updatingIssue.setVersion(updatingIssue.getVersion() + 1);

                    return issueRepository.save(updatingIssue)
                            .flatMap(savedIssue -> outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE, savedIssue.getId(),
                                            EventType.ISSUE_UPDATED, payload)
                                    .then(issueHistoryService.saveIssueHistory(requestId, nodeId, savedIssue.getId(), actorUserId, IssueEventType.UPDATED, payload))
                                    .then(Mono.fromRunnable(() ->
                                            log.debug("[{}][{}] Issue with id: {} successfully updated by user with id: {}",
                                                    requestId, nodeId, issueId, actorUserId)))
                                    .thenReturn(savedIssue)
                            );
                });
    }


    @Override
    public Mono<Issue> deleteIssue(String requestId,
                                   String nodeId,
                                   UUID issueId,
                                   UUID actorUserId
    ) {

        return issueRepository.softDeleteAndReturn(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);
                    return Mono.<Issue>error(new DomainException(DomainStatus.NOT_FOUND, "Issue with id: " + issueId));
                }))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().deleteIssueRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(issue);
                })
                .flatMap(deletedIssue -> {
                    JsonNode payload = payloadSerializer.createIssueDeletedPayload(IssueEventType.DELETED, deletedIssue.getDeletedAt(), actorUserId, deletedIssue.getAssigneeId());
                    return issueHistoryService.saveIssueHistory(requestId, nodeId, deletedIssue.getId(), actorUserId, IssueEventType.DELETED, payload)
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE,
                                    deletedIssue.getId(), EventType.ISSUE_DELETED, payload))
                            .then(Mono.fromRunnable(() ->
                                    log.debug("[{}][{}] Issue with id: {} successfully soft-deleted by user with id: {}",
                                            requestId, nodeId, issueId, actorUserId)))
                            .thenReturn(deletedIssue);
                });
    }

    /**
     * Возвращает IssueWithHistory с лейблами
     */
    @Override
    public Mono<IssueWithHistory> getIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {

        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId));
                }))
                .flatMap(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().getIssueRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(issue);
                })
                .flatMap(issue ->
                        issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issueId, Limit.of(issueProperties.card().maxHistorySize()))
                        .collectList()
                        .zipWith(issueLabelsRepository.findActiveLabelsByIssueId(issueId)
                                .collectList()
                        )
                        .map(tuple->{
                            List<IssueHistory> history = tuple.getT1();
                            List<ProjectLabels> labels = tuple.getT2();
                            return new IssueWithHistory(issue, history, labels);
                        })
                );
    }

    /**
     * Возвращает listIssues
     */
    @Override
    public Mono<PageResult<Issue>> listIssues(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            String statusKey,
            UUID assigneeId,
            UUID labelId,
            Integer page,
            Integer pageSize
    ) {

        int resolvedPage = validatePage(page);
        int resolvedPageSize = validatePageSize(pageSize);
        long offset = (long) resolvedPage * resolvedPageSize;

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                .then(Mono.zip(
                        labelId != null
                                ? issueRepository.countByLabelIdWithFilters(projectId, labelId, statusKey, assigneeId)
                                : issueRepository.countByFilter(projectId, statusKey, assigneeId)
                        ,
                        labelId != null
                                ? issueRepository.findByLabelIdWithFilters(projectId, labelId, statusKey, assigneeId, resolvedPageSize, offset).collectList()
                                : issueRepository.findByFilter(projectId, statusKey, assigneeId, resolvedPageSize, offset).collectList()
                        )
                )
                .map(t ->{
                    List<Issue> listIssue = t.getT2();
                    Long count = t.getT1();
                    return new PageResult<>(listIssue, count);
                });
    }

    private int validatePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            log.warn("Invalid page value: {}, falling back to 0", page);
            return 0;
        }
        return page;
    }

    private int validatePageSize(Integer pageSize) {
        if (pageSize == null) {
            return issueProperties.list().defaultPageSize();
        }
        if (pageSize < 1) {
            log.warn("Invalid pageSize value: {}, falling back to default {}", pageSize, issueProperties.list().defaultPageSize());
            return issueProperties.list().defaultPageSize();
        }
        if (pageSize > issueProperties.list().maxPageSize()) {
            log.warn("Requested pageSize {} exceeds max {}, clamping to max", pageSize, issueProperties.list().maxPageSize());
            return issueProperties.list().maxPageSize();
        }
        return pageSize;
    }

    private boolean isAssigned(Issue issue, UUID assigneeId) {
        return issue.getAssigneeId() != null && issue.getAssigneeId().equals(assigneeId);
    }
}
