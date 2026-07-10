package ru.taska.service.transition;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueTransitionExecutorTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueHistoryRepository historyRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private IssueProperties issueProperties;

    @Mock
    private IssueMapper issueMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private IssueTransitionExecutor executor;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TRANSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String SOURCE_STATUS_KEY = "TODO";
    private static final String TARGET_STATUS_KEY = "IN_PROGRESS";
    private static final int CURRENT_VERSION = 1;
    private static final int UPDATED_VERSION = 2;

    @Test
    @DisplayName("Должен успешно изменить статус и сохранить в БД историю и outbox")
    void executeTransition_shouldChangeStatusSuccessfully() {
        var sourceIssue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .statusKey(SOURCE_STATUS_KEY)
                .version(CURRENT_VERSION)
                .build();

        var updatedIssue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .statusKey(TARGET_STATUS_KEY)
                .version(UPDATED_VERSION)
                .build();


        var history = IssueHistory.builder()
                .issueId(ISSUE_ID)
                .eventType(IssueEventType.TRANSITIONED)
                .build();

        var outboxEvent = OutboxEvent.builder()
                .eventType(IssueEventType.TRANSITIONED.name())
                .build();

        JsonNode payload = JsonNodeFactory.instance.objectNode()
                .put("newStatus", TARGET_STATUS_KEY);

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(sourceIssue));

        Mockito.when(issueRepository.changeStatus(Mockito.any(UUID.class), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(Mono.just(updatedIssue));

        Mockito.when(objectMapper.valueToTree(Mockito.anyMap()))
                .thenReturn(payload);

        Mockito.when(issueMapper.buildIssueHistory(Mockito.any(Issue.class), Mockito.any(IssueEventType.class), Mockito.any(UUID.class)))
                .thenReturn(history);

        Mockito.when(issueMapper.buildOutboxEvent(Mockito.any(Issue.class), Mockito.anyString(), Mockito.any(EventType.class), Mockito.anyString()))
                .thenReturn(outboxEvent);

        Mockito.when(historyRepository.save(Mockito.any(IssueHistory.class)))
                .thenReturn(Mono.just(history));

        Mockito.when(outboxRepository.save(Mockito.any(OutboxEvent.class)))
                .thenReturn(Mono.just(outboxEvent));

        Mockito.when(issueProperties.card().maxHistorySize())
                .thenReturn(10);

        Mockito.when(historyRepository.findByIssueIdOrderByOccurredAtDesc(Mockito.any(UUID.class), Mockito.any(Limit.class)))
                .thenReturn(Flux.just(history));

        StepVerifier.create(executor.executeTransition(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TARGET_STATUS_KEY,
                        TRANSITION_ID,
                        ACTOR_USER_ID
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.getIssue().getId()).isEqualTo(ISSUE_ID);
                    Assertions.assertThat(result.getIssue().getStatusKey()).isEqualTo(TARGET_STATUS_KEY);
                    Assertions.assertThat(result.getIssue().getVersion()).isEqualTo(UPDATED_VERSION);
                    Assertions.assertThat(result.getHistory()).contains(history);
                })
                .verifyComplete();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(issueRepository, Mockito.times(1))
                .changeStatus(ISSUE_ID, TARGET_STATUS_KEY, CURRENT_VERSION);

        Mockito.verify(objectMapper, Mockito.times(1))
                .valueToTree(Mockito.anyMap());

        Mockito.verify(issueMapper, Mockito.times(1))
                .buildIssueHistory(updatedIssue, IssueEventType.TRANSITIONED, ACTOR_USER_ID);

        Mockito.verify(issueMapper, Mockito.times(1))
                .buildOutboxEvent(updatedIssue, AggregateType.ISSUE.getValue(), EventType.ISSUE_TRANSITIONED, REQUEST_ID);

        Mockito.verify(historyRepository, Mockito.times(1))
                .save(history);

        Mockito.verify(outboxRepository, Mockito.times(1))
                .save(outboxEvent);

        Mockito.verify(historyRepository, Mockito.times(1))
                .findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.any(Limit.class));
    }

    @Test
    @DisplayName("Если текущий и целевой статусы одинаковы, то должен вернуть задачу без изменения статуса и без операций записи в БД")
    void executeTransition_shouldReturnCorrectResponse_whenCurrentAndTargetStatusesAreEquals() {
        var issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .statusKey("IN_PROGRESS")
                .build();

        var history = IssueHistory.builder()
                .issueId(ISSUE_ID)
                .eventType(IssueEventType.TRANSITIONED)
                .build();

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueProperties.card().maxHistorySize())
                .thenReturn(10);

        Mockito.when(historyRepository.findByIssueIdOrderByOccurredAtDesc(Mockito.any(UUID.class), Mockito.any(Limit.class)))
                .thenReturn(Flux.just(history));

        StepVerifier.create(executor.executeTransition(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TARGET_STATUS_KEY,
                        TRANSITION_ID,
                        ACTOR_USER_ID
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.getIssue()).isSameAs(issue);
                    Assertions.assertThat(result.getHistory()).hasSize(1);
                    Assertions.assertThat(result.getHistory().getFirst()).isEqualTo(history);
                })
                .verifyComplete();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(historyRepository, Mockito.times(1))
                .findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.any(Limit.class));

        Mockito.verifyNoMoreInteractions(issueRepository, objectMapper, issueMapper, historyRepository, outboxRepository);
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом NOT_FOUND, если задача не найдена")
    void executeTransition_shouldThrowException_whenIssueNotFound() {
        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.executeTransition(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TARGET_STATUS_KEY,
                        TRANSITION_ID,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verifyNoMoreInteractions(issueRepository, objectMapper, issueMapper, historyRepository, outboxRepository);
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом ABORTED при конфликте optimistic lock")
    void executeTransition_shouldThrowException_whenOptimisticLockConflict() {
        var issue = Issue.builder()
                .id(ISSUE_ID)
                .statusKey(SOURCE_STATUS_KEY)
                .version(CURRENT_VERSION)
                .build();

        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueRepository.changeStatus(Mockito.any(UUID.class), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.executeTransition(
                        REQUEST_ID,
                        NODE_ID,
                        ISSUE_ID,
                        TARGET_STATUS_KEY,
                        TRANSITION_ID,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.ABORTED);
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.times(1))
                .findActiveById(ISSUE_ID);

        Mockito.verify(issueRepository, Mockito.times(1))
                .changeStatus(ISSUE_ID, TARGET_STATUS_KEY, CURRENT_VERSION);

        Mockito.verifyNoInteractions(objectMapper, issueMapper, historyRepository, outboxRepository);
    }

}
