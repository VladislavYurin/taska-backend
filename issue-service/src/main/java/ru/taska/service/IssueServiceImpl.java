package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.IssueListProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.PageResult;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    private static final int INIT_VERSION = 1;
    private static final IssueStatus INIT_STATUS = IssueStatus.TODO;
    private static final String ISSUE_AGGREGATE_TYPE = "issue";
    private static final String OUTBOX_EVENT_TYPE = "IssueCreated";
    private static final String ASSIGN_OUTBOX_EVENT_TYPE = "IssueAssigned";

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
                        "", //todo assign issue key. depends on TAS-20: GetProject
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
                    OutboxEvent event = issueMapper.buildOutboxEvent(issue, ISSUE_AGGREGATE_TYPE, OUTBOX_EVENT_TYPE);
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
                                  if (isAssigned(issue,assigneeId)) {
                                      return Mono.just(issue);
                                  }
                                  Issue assignedIssue = issueMapper.setIssueAssignee(issue, assigneeId);
                                  return issueRepository.save(assignedIssue)
                                                        .flatMap(savedIssue -> {
                                                            IssueHistory history = issueMapper
                                                                    .buildIssueHistory(savedIssue, IssueEventType.ASSIGNED, actorUserId);
                                                            OutboxEvent event = issueMapper
                                                                    .buildOutboxEvent(savedIssue, ISSUE_AGGREGATE_TYPE, ASSIGN_OUTBOX_EVENT_TYPE);
                                                            return issueHistoryRepository.save(history)
                                                                                         .then(outboxEventRepository.save(event))
                                                                                         .thenReturn(savedIssue);
                                                        });
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
                .flatMap(issue -> issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issueId)
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
