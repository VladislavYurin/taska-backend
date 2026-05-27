package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueServiceImpl implements IssueService {

    private static final int INIT_VERSION = 1;
    private static final IssueStatus INIT_STATUS = IssueStatus.TODO;
    private static final String ISSUE_AGGREGATE_TYPE = "issue";
    private static final String OUTBOX_EVENT_TYPE = "IssueCreated";

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
    public Mono<IssueWithHistory> getIssue(UUID issueId) {
        return issueRepository.findByIdAndDeletedAtIsNull(issueId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId)))
                .flatMap(issue -> issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(issueId)
                        .collectList()
                        .map(history -> new IssueWithHistory(issue, history)));
    }

    @Override
    public Flux<Issue> listIssues(UUID projectId, IssueStatus status, UUID assigneeId) {
        return issueRepository.findByFilter(projectId, status, assigneeId);
    }
}
