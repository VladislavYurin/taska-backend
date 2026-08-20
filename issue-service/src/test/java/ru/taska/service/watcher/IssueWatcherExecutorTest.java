package ru.taska.service.watcher;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.IssueWatcher;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueWatcherExecutorTest {

    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WATCHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID WATCHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    @Mock
    private IssueWatcherRepository issueWatcherRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private PayloadSerializer payloadSerializer;

    @InjectMocks
    private IssueWatcherExecutor executor;

    private IssueWatcher watcher;
    private ObjectNode payload;

    @BeforeEach
    void setUp() {
        watcher = IssueWatcher.builder()
                .id(WATCHER_ID)
                .issueId(ISSUE_ID)
                .projectId(PROJECT_ID)
                .userId(WATCHER_USER_ID)
                .createdBy(ACTOR_USER_ID)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        payload = JsonNodeFactory.instance.objectNode()
                .put("issueId", ISSUE_ID.toString());
    }

    @Test
    @DisplayName("executeWatch: должен создать подписку и сохранить outbox-событие")
    void executeWatch_shouldInsertAndPublishOutbox() {
        Mockito.when(issueWatcherRepository.insertIfAbsent(ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.just(watcher));
        Mockito.when(payloadSerializer.createIssueWatchedPayload(
                        ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .thenReturn(payload);
        Mockito.when(outboxEventService.saveOutboxEvent(
                        REQUEST_ID, NODE_ID, AggregateType.ISSUE, ISSUE_ID,
                        EventType.ISSUE_WATCHED, payload))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .expectNext(watcher)
                .verifyComplete();

        Mockito.verify(issueWatcherRepository).insertIfAbsent(ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID);
        Mockito.verify(outboxEventService).saveOutboxEvent(
                REQUEST_ID, NODE_ID, AggregateType.ISSUE, ISSUE_ID, EventType.ISSUE_WATCHED, payload);
        Mockito.verify(issueWatcherRepository, Mockito.never())
                .findByIssueIdAndUserId(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("executeWatch: при уже существующей подписке должен вернуть её без outbox")
    void executeWatch_shouldBeIdempotentWhenAlreadyWatched() {
        Mockito.when(issueWatcherRepository.insertIfAbsent(ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .thenReturn(Mono.empty());
        Mockito.when(issueWatcherRepository.findByIssueIdAndUserId(ISSUE_ID, WATCHER_USER_ID))
                .thenReturn(Mono.just(watcher));

        StepVerifier.create(executor.executeWatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .expectNext(watcher)
                .verifyComplete();

        Mockito.verify(issueWatcherRepository).findByIssueIdAndUserId(ISSUE_ID, WATCHER_USER_ID);
        Mockito.verifyNoInteractions(payloadSerializer, outboxEventService);
    }

    @Test
    @DisplayName("executeUnwatch: должен удалить подписку и сохранить outbox-событие")
    void executeUnwatch_shouldDeleteAndPublishOutbox() {
        Mockito.when(issueWatcherRepository.deleteByIssueIdAndUserId(ISSUE_ID, WATCHER_USER_ID))
                .thenReturn(Mono.just(1L));
        Mockito.when(payloadSerializer.createIssueUnwatchedPayload(
                        ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .thenReturn(payload);
        Mockito.when(outboxEventService.saveOutboxEvent(
                        REQUEST_ID, NODE_ID, AggregateType.ISSUE, ISSUE_ID,
                        EventType.ISSUE_UNWATCHED, payload))
                .thenReturn(Mono.empty());

        StepVerifier.create(executor.executeUnwatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .assertNext(removed -> Assertions.assertThat(removed).isTrue())
                .verifyComplete();

        Mockito.verify(outboxEventService).saveOutboxEvent(
                REQUEST_ID, NODE_ID, AggregateType.ISSUE, ISSUE_ID, EventType.ISSUE_UNWATCHED, payload);
    }

    @Test
    @DisplayName("executeUnwatch: если подписки не было — false без outbox")
    void executeUnwatch_shouldReturnFalseWhenNothingDeleted() {
        Mockito.when(issueWatcherRepository.deleteByIssueIdAndUserId(ISSUE_ID, WATCHER_USER_ID))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(executor.executeUnwatch(
                        REQUEST_ID, NODE_ID, ISSUE_ID, PROJECT_ID, WATCHER_USER_ID, ACTOR_USER_ID))
                .assertNext(removed -> Assertions.assertThat(removed).isFalse())
                .verifyComplete();

        Mockito.verifyNoInteractions(payloadSerializer, outboxEventService);
    }
}
