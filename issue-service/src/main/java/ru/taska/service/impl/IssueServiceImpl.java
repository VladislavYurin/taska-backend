package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueListProperties;
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

    private final IssueListProperties issueListProperties;
    private final ProjectCounterRepository projectCounterRepository;
    private final IssueRepository issueRepository;
    private final IssueHistoryRepository issueHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IssueMapper issueMapper;

    @Override
    @Transactional
    public Mono<Issue> createIssue(
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    ) {
        //todo add membership check. depends on TAS-21: CheckProjectRole

        return projectCounterRepository.getNextIssueNumberAndIncrement(projectId)
                .map(number -> issueMapper.buildIssue(
                        projectId,
                        number,
                        UUID.randomUUID().toString(), //todo assign issue key. depends on TAS-20: GetProject (временно добавил автогенерацию UUID)
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
                            .thenReturn(issue);
                });
    }

    @Override
    @Transactional
    public Mono<Issue> assignIssue(UUID issueId, UUID assigneeId, UUID actorUserId) {
        //todo add membership check. depends on TAS-21: CheckProjectRole
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.NOT_FOUND,
                        "Issue with id: " + issueId + " not found"
                )))
                .flatMap(issue -> {
                    if (isAssigned(issue, assigneeId)) {
                        return Mono.just(issue);
                    }
                    Issue assignedIssue = issueMapper.setIssueAssignee(issue, assigneeId);
                    return issueRepository.save(assignedIssue)
                            .flatMap(savedIssue -> {
                                IssueHistory history = issueMapper
                                        .buildIssueHistory(savedIssue, IssueEventType.ASSIGNED, actorUserId);
                                OutboxEvent event = issueMapper
                                        .buildOutboxEvent(savedIssue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_ASSIGNED);
                                return issueHistoryRepository.save(history)
                                        .then(outboxEventRepository.save(event))
                                        .thenReturn(savedIssue);
                            });
                });
    }

    @Override
    public Mono<Issue> deleteIssue(String requestId, String nodeId, UUID issueId, UUID actorUserId) {
        //todo add membership check. depends on TAS-21: CheckProjectRole
        return issueRepository.softDeleteAndReturn(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Issue with id: " + issueId + " was not found");
                    return Mono.<Issue>error(new DomainException(DomainStatus.NOT_FOUND, "Issue with id: " + issueId));
                }))
                .flatMap(deletedIssue -> {
                    IssueHistory history = issueMapper.buildIssueHistory(deletedIssue, IssueEventType.DELETED, actorUserId);
                    OutboxEvent outboxEvent = issueMapper.buildOutboxEvent(deletedIssue, ISSUE_AGGREGATE_TYPE, EventType.ISSUE_DELETED);

                    return issueHistoryRepository.save(history)
                            .then(outboxEventRepository.save(outboxEvent))
                            .then(Mono.fromRunnable(() ->
                                    log.info("[{}][{}] Issue with id: {} successfully soft-deleted by user with id: {}",
                                            requestId, nodeId, issueId, actorUserId)))
                            .thenReturn(deletedIssue);
                });
    }

    private boolean isAssigned(Issue issue, UUID assigneeId) {
        return issue.getAssigneeId() != null && issue.getAssigneeId().equals(assigneeId);
    }


    @Override
    public Mono<IssueWithHistory> getIssue(UUID issueId) {
        //todo add membership check. depends on TAS-21: CheckProjectRole

        return issueRepository.findByIdAndDeletedAtIsNull(issueId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId)))
                .flatMap(issue -> issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issueId, Limit.of(issueCardMaxHistorySize))
                        .collectList()
                        .map(history -> new IssueWithHistory(issue, history)));
    }

    @Override
    public Mono<PageResult<Issue>> listIssues(UUID projectId, IssueStatus status, UUID assigneeId, Integer page, Integer pageSize) {
        //todo add membership check. depends on TAS-21: CheckProjectRole

        if (projectId == null) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "projectId is required"));
        }
        int resolvedPage = validatePage(page);
        int resolvedPageSize = validatePageSize(pageSize);
        long offset = (long) resolvedPage * resolvedPageSize;
        return Mono.zip(
                issueRepository.countByFilter(projectId, status, assigneeId),
                issueRepository.findByFilter(projectId, status, assigneeId, resolvedPageSize, offset).collectList()
        ).map(t -> new PageResult<>(t.getT2(), t.getT1()));
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
