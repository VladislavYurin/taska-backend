package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.event.EventType;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.IssueWatcherService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.kafka.OutboxEventScheduler;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.grpc.server.port=0")
class IssueWatcherIntegrationTest extends AbstractIT {
    private static final String REQUEST_ID = "unwatch-regression";
    private static final String NODE_ID = "test-node";

    @Autowired
    private IssueWatcherService service;
    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private IssueWatcherRepository watcherRepository;
    @Autowired
    private OutboxEventRepository outboxRepository;

    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;
    @MockitoBean
    private OutboxEventScheduler outboxScheduler;

    private Issue issue;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        issue = issueRepository.save(Issue.builder()
                .projectId(UUID.randomUUID()).issueNumber(1).issueKey("TEST-" + UUID.randomUUID())
                .issueType(IssueType.TASK).summary("Unwatch regression").statusKey("TODO")
                .priority(IssuePriority.MEDIUM).reporterId(actorId).version(1).build())
                .block(Duration.ofSeconds(10));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @ParameterizedTest(name = "manageOtherWatcher={0}")
    @ValueSource(booleans = {false, true})
    void watchUnwatchUnwatch_matchesDatabaseAndPublishesOnlyActualChanges(boolean manageOtherWatcher) {
        UUID targetUserId = manageOtherWatcher ? UUID.randomUUID() : null;
        UUID watcherUserId = manageOtherWatcher ? targetUserId : actorId;

        StepVerifier.create(service.watchIssue(REQUEST_ID, NODE_ID, issue.getId(), actorId, targetUserId))
                .assertNext(result -> Assertions.assertThat(result.watchersCount()).isEqualTo(1))
                .expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(service.unwatchIssue(REQUEST_ID, NODE_ID, issue.getId(), actorId, targetUserId))
                .assertNext(result -> {
                    Assertions.assertThat(result.removed()).isTrue();
                    Assertions.assertThat(result.watchersCount()).isZero();
                }).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(watcherRepository.existsByIssueIdAndUserId(issue.getId(), watcherUserId))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(service.unwatchIssue(REQUEST_ID, NODE_ID, issue.getId(), actorId, targetUserId))
                .assertNext(result -> {
                    Assertions.assertThat(result.removed()).isFalse();
                    Assertions.assertThat(result.watchersCount()).isZero();
                }).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(outboxRepository.findAll()
                        .filter(event -> issue.getId().equals(event.getAggregateId()))
                        .map(event -> event.getEventType()).collectList())
                .assertNext(types -> Assertions.assertThat(types).containsExactlyInAnyOrder(
                        EventType.ISSUE_WATCHED.getValue(), EventType.ISSUE_UNWATCHED.getValue()))
                .expectComplete().verify(Duration.ofSeconds(10));
    }
}
