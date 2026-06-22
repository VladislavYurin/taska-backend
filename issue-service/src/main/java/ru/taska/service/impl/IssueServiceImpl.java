package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.config.props.IssueListProperties;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.PageResult;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;
import ru.taska.service.IssueService;
import ru.taska.transport.grpc.GrpcProjectServiceClient;
import ru.taska.transport.grpc.ProjectRoleChecker;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    @Value("${issue.card.max-history-size}")
    private int issueCardMaxHistorySize;

    private static final int INIT_VERSION = 1;
    private static final IssueStatus INIT_STATUS = IssueStatus.TODO;
    private static final String ISSUE_AGGREGATE_TYPE = "issue";

    private final IssueProperties issueProperties;
    private final IssueListProperties issueListProperties;
    private final GrpcProjectServiceClient grpcProjectServiceClient;
    private final ProjectCounterRepository projectCounterRepository;
    private final IssueRepository issueRepository;
    private final IssueHistoryRepository issueHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IssueMapper issueMapper;
    private final ProjectRoleChecker projectRoleChecker;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Mono<Issue> createIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    ) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().createIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, reporterId, allowedRoles)
                .then(grpcProjectServiceClient.getProjectKey(requestId, nodeId, projectId))
                .flatMap(projectKey -> projectCounterRepository.getNextIssueNumberAndIncrement(projectId)
                        .map(number -> issueMapper.buildIssue(
                                projectId,
                                number,
                                projectKey + "-" + number,
                                issueType,
                                summary,
                                description,
                                priority,
                                reporterId,
                                INIT_STATUS,
                                INIT_VERSION))
                        .flatMap(issueRepository::save)
                        .flatMap(issue -> {
                            IssueHistory history = issueMapper.buildIssueHistory(issue, IssueEventType.CREATED, reporterId);
                            OutboxEvent event = issueMapper.buildOutboxEvent(issue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_CREATED);
                            return issueHistoryRepository.save(history)
                                    .then(outboxEventRepository.save(event))
                                    .then(Mono.fromRunnable(() ->
                                            log.debug("[{}][{}] Issue successfully created by user with id: {}",
                                                    requestId, nodeId, reporterId)))
                                    .thenReturn(issue);
                        }));
    }

    @Override
    @Transactional
    public Mono<Issue> assignIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID assigneeId,
            UUID actorUserId
    ) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().assignIssueRoles();

        Mono<Void> actorCheck = projectRoleChecker.checkProjectRole(
                requestId, nodeId, projectId, actorUserId, allowedRoles
        );

        Mono<Void> assigneeCheck = actorUserId.equals(assigneeId)
                ? Mono.empty()
                : projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, assigneeId, allowedRoles);

        return actorCheck
                .then(assigneeCheck)
                .then(issueRepository.findActiveById(issueId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);

                            return Mono.error(new DomainException(
                                    DomainStatus.NOT_FOUND,
                                    "Issue with id: " + issueId + " not found"
                            ));
                        }))
                        .flatMap(issue -> {
                            if (isAssigned(issue, assigneeId)) {
                                return Mono.just(issue);
                            }

                            ObjectNode historyPayload = objectMapper.valueToTree(Map.of(
                                    "oldAssigneeId", Optional.ofNullable(issue.getAssigneeId()),
                                    "newAssigneeId", assigneeId
                            ));

                            Issue assignedIssue = issueMapper.setIssueAssignee(issue, assigneeId);
                            return issueRepository.save(assignedIssue)
                                    .flatMap(savedIssue -> {
                                        IssueHistory history = issueMapper
                                                .buildIssueHistory(savedIssue, IssueEventType.ASSIGNED, actorUserId);
                                        history.setPayload(historyPayload);
                                        OutboxEvent event = issueMapper
                                                .buildOutboxEvent(savedIssue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_ASSIGNED);
                                        return issueHistoryRepository.save(history)
                                                .then(outboxEventRepository.save(event))
                                                .then(Mono.fromRunnable(() ->
                                                        log.debug("[{}][{}] User with id: {} successfully assigned to issue with id: {}",
                                                                requestId, nodeId, assigneeId, issueId)))
                                                .thenReturn(savedIssue);
                                    });
                        }));
    }

    @Override
    @Transactional
    public Mono<Issue> updateIssue(String requestId, String nodeId, UUID projectId,  UUID issueId, UUID actorUserId,
                                   String summary, String description, IssuePriority priority) {

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().updateIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                .then(issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}]Issue with id: {} was not found", requestId, nodeId, issueId);
                    return Mono.<Issue>error(new DomainException(DomainStatus.NOT_FOUND,
                            "Issue with id: " + issueId + " was not found"));
                }))
                .flatMap(issue -> {
                    if (!summary.equals(issue.getSummary())
                            || !Objects.equals(description, issue.getDescription()) || !priority.equals(issue.getPriority())) {

                        ObjectNode historyPayload = objectMapper.valueToTree(Map.of(
                                "oldSummary", issue.getSummary(),
                                "newSummary", summary,
                                "oldDescription", Optional.ofNullable(issue.getDescription()),
                                "newDescription", Optional.ofNullable(description),
                                "oldPriority", issue.getPriority(),
                                "newPriority", String.valueOf(priority)
                        ));

                        issue.setSummary(summary);
                        issue.setDescription(description);
                        issue.setPriority(priority);
                        issue.setUpdatedAt(Instant.now());
                        issue.setVersion(issue.getVersion() + 1);

                        IssueHistory history = issueMapper.buildIssueHistory(issue, IssueEventType.UPDATED, actorUserId);
                        history.setPayload(historyPayload);
                        OutboxEvent event = issueMapper.buildOutboxEvent(issue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_UPDATED);

                        return issueRepository.save(issue)
                                .flatMap(savedIssue -> issueHistoryRepository.save(history)
                                        .then(outboxEventRepository.save(event))
                                        .then(Mono.fromRunnable(() ->
                                                log.info("[{}][{}] Issue with id: {} successfully updated by user with id: {}",
                                                        requestId, nodeId, issueId, actorUserId)))
                                        .thenReturn(savedIssue)
                                );
                    }

                    return Mono.defer(() -> {
                        log.info("[{}][{}] Issue with id: {} equals updated issue by user with id: {}",
                                requestId, nodeId, issueId, actorUserId);
                        return Mono.just(issue);
                    });
                }));
    }

    @Override
    public Mono<Issue> deleteIssue(String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId
    ) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().deleteIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                .then(issueRepository.softDeleteAndReturn(issueId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);
                            return Mono.<Issue>error(new DomainException(DomainStatus.NOT_FOUND, "Issue with id: " + issueId));
                        }))
                        .flatMap(deletedIssue -> {
                            IssueHistory history = issueMapper.buildIssueHistory(deletedIssue, IssueEventType.DELETED, actorUserId);
                            OutboxEvent outboxEvent = issueMapper.buildOutboxEvent(deletedIssue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_DELETED);

                            return issueHistoryRepository.save(history)
                                    .then(outboxEventRepository.save(outboxEvent))
                                    .then(Mono.fromRunnable(() ->
                                            log.debug("[{}][{}] Issue with id: {} successfully soft-deleted by user with id: {}",
                                                    requestId, nodeId, issueId, actorUserId)))
                                    .thenReturn(deletedIssue);
                        }));
    }

    private boolean isAssigned(Issue issue, UUID assigneeId) {
        return issue.getAssigneeId() != null && issue.getAssigneeId().equals(assigneeId);
    }


    @Override
    public Mono<IssueWithHistory> getIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId
    ) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().getIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                .then(issueRepository.findByIdAndDeletedAtIsNull(issueId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("[{}][{}] Issue with id: " + issueId + " was not found", requestId, nodeId);

                            return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId));
                        }))
                        .flatMap(issue -> issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issueId, Limit.of(issueCardMaxHistorySize))
                                .collectList()
                                .map(history -> new IssueWithHistory(issue, history))));
    }

    @Override
    public Mono<PageResult<Issue>> listIssues(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            IssueStatus status,
            UUID assigneeId,
            Integer page,
            Integer pageSize
    ) {
        int resolvedPage = validatePage(page);
        int resolvedPageSize = validatePageSize(pageSize);
        long offset = (long) resolvedPage * resolvedPageSize;

        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listIssueRoles();

        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                .then(Mono.zip(
                        issueRepository.countByFilter(projectId, status, assigneeId),
                        issueRepository.findByFilter(projectId, status, assigneeId, resolvedPageSize, offset)
                                .collectList()
                ).map(t -> new PageResult<>(t.getT2(), t.getT1())));
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
            return issueListProperties.defaultPageSize();
        }
        if (pageSize < 1) {
            log.warn("Invalid pageSize value: {}, falling back to default {}", pageSize, issueListProperties.defaultPageSize());
            return issueListProperties.defaultPageSize();
        }
        if (pageSize > issueListProperties.maxPageSize()) {
            log.warn("Requested pageSize {} exceeds max {}, clamping to max", pageSize, issueListProperties.maxPageSize());
            return issueListProperties.maxPageSize();
        }
        return pageSize;
    }
}
