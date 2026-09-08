package ru.taska.service.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueWatcher;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueAutoWatchServiceTest {

    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueWatcherExecutor issueWatcherExecutor;

    @InjectMocks
    private IssueAutoWatchService autoWatchService;

    @Test
    @DisplayName("watchReporterOnCreate: при enabled вызывает executeWatch")
    void watchReporterOnCreate_enabled() {
        Issue issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .reporterId(REPORTER_ID)
                .build();

        Mockito.when(issueProperties.autoWatch()).thenReturn(IssueProperties.AutoWatch.enabled());
        Mockito.when(issueWatcherExecutor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, REPORTER_ID, REPORTER_ID))
                .thenReturn(Mono.just(IssueWatcher.builder().id(UUID.randomUUID()).build()));

        StepVerifier.create(autoWatchService.watchReporterOnCreate(REQUEST_ID, NODE_ID, issue))
                .verifyComplete();

        Mockito.verify(issueWatcherExecutor).executeWatch(
                REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, REPORTER_ID, REPORTER_ID);
    }

    @Test
    @DisplayName("watchReporterOnCreate: при disabled не вызывает executor")
    void watchReporterOnCreate_disabled() {
        Issue issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .reporterId(REPORTER_ID)
                .build();

        Mockito.when(issueProperties.autoWatch()).thenReturn(IssueProperties.AutoWatch.disabled());

        StepVerifier.create(autoWatchService.watchReporterOnCreate(REQUEST_ID, NODE_ID, issue))
                .verifyComplete();

        Mockito.verifyNoInteractions(issueWatcherExecutor);
    }

    @Test
    @DisplayName("watchAssigneeOnAssign: при enabled вызывает executeWatch для assignee")
    void watchAssigneeOnAssign_enabled() {
        Issue issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .assigneeId(ASSIGNEE_ID)
                .build();

        Mockito.when(issueProperties.autoWatch()).thenReturn(IssueProperties.AutoWatch.enabled());
        Mockito.when(issueWatcherExecutor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, ASSIGNEE_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(IssueWatcher.builder().id(UUID.randomUUID()).build()));

        StepVerifier.create(autoWatchService.watchAssigneeOnAssign(
                        REQUEST_ID, NODE_ID, issue, ACTOR_USER_ID))
                .verifyComplete();

        Mockito.verify(issueWatcherExecutor).executeWatch(
                REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, ASSIGNEE_ID, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("watchAssigneeOnAssign: если assignee null — no-op")
    void watchAssigneeOnAssign_nullAssignee() {
        Issue issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .assigneeId(null)
                .build();

        Mockito.when(issueProperties.autoWatch()).thenReturn(IssueProperties.AutoWatch.enabled());

        StepVerifier.create(autoWatchService.watchAssigneeOnAssign(
                        REQUEST_ID, NODE_ID, issue, ACTOR_USER_ID))
                .verifyComplete();

        Mockito.verifyNoInteractions(issueWatcherExecutor);
    }

    @Test
    @DisplayName("watchAssigneeOnAssign: при disabled не вызывает executor")
    void watchAssigneeOnAssign_disabled() {
        Issue issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .assigneeId(ASSIGNEE_ID)
                .build();

        Mockito.when(issueProperties.autoWatch()).thenReturn(IssueProperties.AutoWatch.disabled());

        StepVerifier.create(autoWatchService.watchAssigneeOnAssign(
                        REQUEST_ID, NODE_ID, issue, ACTOR_USER_ID))
                .verifyComplete();

        Mockito.verifyNoInteractions(issueWatcherExecutor);
    }
}
