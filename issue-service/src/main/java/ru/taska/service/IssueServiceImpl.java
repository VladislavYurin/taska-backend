package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.OutboxEvent;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectCounterRepository;
import tools.jackson.databind.ObjectMapper;

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
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

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
                .map(number -> Issue.builder()
                        .projectId(projectId)
                        .issueNumber(number)
                        .issueKey("") //todo depends on TAS-20: GetProject
                        .issueType(issueType)
                        .summary(summary)
                        .description(description)
                        .statusKey(INIT_STATUS)
                        .priority(priority)
                        .reporterId(reporterId)
                        .version(INIT_VERSION)
                        .build()
                )
                .flatMap(issueRepository::save)
                .flatMap(issue -> {
                    OutboxEvent event = OutboxEvent.builder()
                            .aggregateType(ISSUE_AGGREGATE_TYPE)
                            .aggregateId(issue.getId())
                            .eventType(OUTBOX_EVENT_TYPE)
                            .payload(objectMapper.valueToTree(issue))
                            .build();
                    return outboxEventRepository.save(event).thenReturn(issue);
                });
    }
}
