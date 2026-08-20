package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import ru.taska.domain.IdempotencyKey;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.util.RequestHasher;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

class CreateIssueTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;
    private String expectedHash;
    private MockedStatic<RequestHasher> mockedRequestHasher;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );

        expectedHash = "mocked_request_hash";
        mockedRequestHasher = Mockito.mockStatic(RequestHasher.class);
        mockedRequestHasher.when(() -> RequestHasher.hashIssueCreateRequest(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(expectedHash);

        Mockito.lenient().when(issueProperties.allowedRoles().createIssueRoles()).thenReturn(allowedRoles);
        Mockito.lenient().when(issueProperties.idempotencyKeyTtl().ttl()).thenReturn(Duration.ofHours(24));

        Mockito.lenient().when(projectRoleChecker.checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, REPORTER_ID, allowedRoles))
                .thenReturn(Mono.empty());

        Mockito.lenient().when(grpcProjectServiceClient.getProjectKey(REQUEST_ID, NODE_ID, PROJECT_ID))
                .thenReturn(Mono.just("TSK"));
        Mockito.lenient().when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1));
        Mockito.lenient().when(idempotencyKeyRepository.findByUserIdAndKey(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        Mockito.lenient().when(issueRepository.save(Mockito.any()))
                .thenAnswer(invocation -> {
                    Issue issue = invocation.getArgument(0);
                    return Mono.just(issue.toBuilder().id(UUID.randomUUID()).build());
                });

        Mockito.lenient().when(issueHistoryService.saveIssueCreateHistory(Mockito.anyString(), Mockito.anyString(), Mockito.any(Issue.class)))
                .thenReturn(Mono.empty());
        Mockito.lenient().when(outboxEventService.saveOutboxEvent(Mockito.anyString(), Mockito.anyString(), Mockito.any(AggregateType.class), Mockito.any(Issue.class)))
                .thenReturn(Mono.empty());

        IdempotencyKey mockedKey = new IdempotencyKey();
        Mockito.doReturn(mockedKey)
                .when(issueMapper).buildIdempotencyKey(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.lenient().when(idempotencyKeyRepository.save(Mockito.any()))
                .thenAnswer(invocation -> Mono.just((IdempotencyKey) invocation.getArgument(0)));

        Mockito.lenient().when(issueAutoWatchService.watchReporterOnCreate(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(Issue.class)))
                .thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        if (mockedRequestHasher != null) {
            mockedRequestHasher.close();
        }
    }

    @Test
    @DisplayName("Должен вызывать счетчик проекта при создании задачи")
    void shouldCallProjectCounterOnIssueCreation() {
        issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.TASK,
                "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        Mockito.verify(issueProperties.allowedRoles()).createIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, REPORTER_ID, allowedRoles
        );
        Mockito.verify(projectCounterRepository, Mockito.times(1)).getNextIssueNumberAndIncrement(PROJECT_ID);
        Mockito.verify(issueAutoWatchService).watchReporterOnCreate(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(Issue.class));
    }

    @Test
    @DisplayName("Должен присваивать задаче номер, полученный из счетчика")
    void shouldAssignIssueNumberFromCounter() {
        int nextIssueNumber = 5;
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(nextIssueNumber));

        Issue result = issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.BUG,
                "Ошибка", "Описание", IssuePriority.HIGH, REPORTER_ID
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getIssueNumber()).isEqualTo(nextIssueNumber);

        Mockito.verify(issueProperties.allowedRoles()).createIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, REPORTER_ID, allowedRoles
        );
    }

    @Test
    @DisplayName("Должен инкрементировать счетчик для каждой создаваемой задачи")
    void shouldIncrementCounterForEachCreatedIssue() {
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1))
                .thenReturn(Mono.just(2));

        Issue first = issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.TASK,
                "Задача 1", null, IssuePriority.LOW, REPORTER_ID
        ).block();
        Issue second = issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_2, PROJECT_ID, IssueType.TASK,
                "Задача 2", null, IssuePriority.LOW, REPORTER_ID
        ).block();

        Mockito.verify(issueProperties.allowedRoles(), Mockito.times(2))
                .createIssueRoles();
        Mockito.verify(projectRoleChecker, Mockito.times(2)).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, REPORTER_ID, allowedRoles
        );
        Mockito.verify(projectCounterRepository, Mockito.times(2)).getNextIssueNumberAndIncrement(PROJECT_ID);
        Assertions.assertThat(first).isNotNull();
        Assertions.assertThat(second).isNotNull();
        Assertions.assertThat(first.getIssueNumber()).isEqualTo(1);
        Assertions.assertThat(second.getIssueNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Должен вызывать метод сохранения Outbox события при создании задачи")
    void shouldSaveOutboxEventOnIssueCreation() {
        issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.TASK,
                "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        Mockito.verify(outboxEventService, Mockito.times(1))
                .saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(AggregateType.class), Mockito.any(Issue.class));

        Mockito.verify(issueProperties.allowedRoles()).createIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, REPORTER_ID, allowedRoles
        );
    }
}