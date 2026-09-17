package ru.taska.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.test.StepVerifier;
import ru.taska.domain.Notification;
import ru.taska.domain.ProcessedEvent;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.repository.ProcessedEventRepository;
import ru.taska.service.EmailSenderService;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationHandler: интеграционные тесты")
class NotificationHandlerIT extends AbstractIT {

    // ==================== Поля payload ====================

    private static final String FIELD_ISSUE_ID = "issueId";
    private static final String FIELD_AUTHOR_USER_ID = "authorUserId";
    private static final String FIELD_ACTOR_USER_ID = "actorUserId";
    private static final String FIELD_ASSIGNEE_ID = "assigneeId";
    private static final String FIELD_CREATED_BY = "createdBy";
    private static final String FIELD_DELETED_BY = "deletedBy";
    private static final String FIELD_SOURCE_ISSUE_ID = "sourceIssueId";
    private static final String FIELD_TARGET_ISSUE_ID = "targetIssueId";
    private static final String FIELD_LINK_TYPE = "linkType";
    private static final String FIELD_WATCHER_IDS = "watcherIds";
    private static final String FIELD_BODY = "body";
    private static final String LINK_TYPE_BLOCKS = "BLOCKS";
    private static final String AGGREGATE_TYPE_ISSUE = "ISSUE";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired private NotificationEventHandler handler;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private EmailSenderService emailSenderService;

    private UUID eventId;
    private UUID issueId;
    private UUID actorId;
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll().block();
        processedEventRepository.deleteAll().block();

        eventId = UUID.randomUUID();
        issueId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
    }

    // ==================== Happy path ====================

    @Nested
    @DisplayName("Успешный сценарий")
    class HappyPathTests {

        @Test
        @DisplayName("ISSUE_UPDATED уведомляет наблюдателей и исполнителя, исключая актора")
        void shouldNotifyWatchersAndAssigneeOnUpdated() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatchers(payload, userA, userB, actorId);

            TaskaEvent event = event(EventType.ISSUE_UPDATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB)
                    .doesNotContain(actorId);
        }

        @Test
        @DisplayName("ISSUE_COMMENT_CREATED уведомляет наблюдателей, исключая автора")
        void shouldNotifyWatchersExceptAuthorOnCommentCreated() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatchers(payload, userA, userB, actorId);

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB)
                    .doesNotContain(actorId);
        }

        @Test
        @DisplayName("ISSUE_TRANSITIONED уведомляет наблюдателей и исполнителя, исключая актора")
        void shouldNotifyWatchersAndAssigneeOnTransitioned() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatchers(payload, userA, userB, actorId);

            TaskaEvent event = event(EventType.ISSUE_TRANSITIONED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB)
                    .doesNotContain(actorId);
        }

        @Test
        @DisplayName("ISSUE_ASSIGNED уведомляет исполнителя и наблюдателей, исключая актора")
        void shouldNotifyAssigneeAndWatchersOnAssigned() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatchers(payload, userB);

            TaskaEvent event = event(EventType.ISSUE_ASSIGNED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB);
        }

        @Test
        @DisplayName("ISSUE_LINK_CREATED уведомляет наблюдателей, исключая актора")
        void shouldNotifyWatchersOnLinkCreated() {
            ObjectNode payload = linkPayload();
            payload.put(FIELD_CREATED_BY, actorId.toString());
            setWatchers(payload, userA, userB, actorId);

            TaskaEvent event = event(EventType.ISSUE_LINK_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB);
        }

        @Test
        @DisplayName("ISSUE_LINK_DELETED уведомляет наблюдателей, исключая актора")
        void shouldNotifyWatchersOnLinkDeleted() {
            ObjectNode payload = linkPayload();
            payload.put(FIELD_DELETED_BY, actorId.toString());
            setWatchers(payload, userA, userB, actorId);

            TaskaEvent event = event(EventType.ISSUE_LINK_DELETED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB);
        }
    }

    // ==================== Дедупликация ====================

    @Nested
    @DisplayName("Дедупликация")
    class DeduplicationTests {

        @Test
        @DisplayName("Повторная доставка того же eventId не создаёт новые уведомления")
        void shouldNotCreateDuplicatesOnDuplicateEvent() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatchers(payload, userA, userB);

            TaskaEvent event = event(EventType.ISSUE_TRANSITIONED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();
            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
            assertThat(all).extracting(Notification::getUserId)
                    .containsExactlyInAnyOrder(userA, userB);
        }

        @Test
        @DisplayName("Дубликат исполнителя и наблюдателя устраняется")
        void shouldDedupAssigneeAndWatcher() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatchers(payload, userA, userA, userB, userB);

            TaskaEvent event = event(EventType.ISSUE_ASSIGNED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);
        }

        @Test
        @DisplayName("ProcessedEvent сохраняется с правильными eventId и sourceType")
        void shouldSaveProcessedEvent() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");
            setWatchers(payload, userA);

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<ProcessedEvent> all = processedEventRepository.findAll().collectList().block();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getEventId()).isEqualTo(eventId);
            assertThat(all.get(0).getSourceType()).isEqualTo(AGGREGATE_TYPE_ISSUE);
            assertThat(all.get(0).getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("Уникальный индекс отклоняет повтор (source_event_id, user_id)")
        void shouldRejectDuplicateSourceEventAndUser() {
            // Прямая попытка вставить два уведомления с одинаковым (source_event_id, user_id)
            // Проверяет, что индекс из миграции 0004 реально работает.
            Notification n1 = Notification.builder()
                    .userId(userA)
                    .notificationType(ru.taska.domain.NotificationType.ISSUE_COMMENT_CREATED)
                    .title("test")
                    .body("body")
                    .sourceEventId(eventId)
                    .createdAt(Instant.now())
                    .build();

            notificationRepository.save(n1).block();

            Notification n2 = n1.toBuilder().id(null).build();

            StepVerifier.create(notificationRepository.save(n2))
                    .expectError()  // DuplicateKeyException
                    .verify();

            assertThat(notificationRepository.findAll().collectList().block()).hasSize(1);
        }
    }

    // ==================== Пустые наблюдатели ====================

    @Nested
    @DisplayName("Пустые наблюдатели")
    class EmptyWatchersTests {

        @Test
        @DisplayName("Пустой список наблюдателей не приводит к ошибке")
        void shouldNotFailOnEmptyWatchers() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");
            setWatchers(payload);

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            assertThat(notificationRepository.findAll().collectList().block()).isEmpty();
            // ProcessedEvent всё равно сохранён
            assertThat(processedEventRepository.findAll().collectList().block()).hasSize(1);
        }

        @Test
        @DisplayName("Отсутствие поля watcherIds не приводит к ошибке")
        void shouldNotFailWhenWatchersMissing() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            assertThat(notificationRepository.findAll().collectList().block()).isEmpty();
        }

        @Test
        @DisplayName("Все наблюдатели — актор: уведомлений нет, событие обработано")
        void shouldNotCreateNotificationsWhenAllWatchersAreActor() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");
            setWatchers(payload, actorId);

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            assertThat(notificationRepository.findAll().collectList().block()).isEmpty();
            assertThat(processedEventRepository.findAll().collectList().block()).hasSize(1);
        }
    }

    // ==================== Типы уведомлений ====================

    @Nested
    @DisplayName("Типы и поля уведомлений")
    class NotificationFieldsTests {

        @Test
        @DisplayName("Созданные уведомления имеют правильный тип и sourceEventId")
        void shouldCreateNotificationsWithCorrectTypeAndSourceEventId() {
            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");
            setWatchers(payload, userA);

            TaskaEvent event = event(EventType.ISSUE_COMMENT_CREATED, payload);

            StepVerifier.create(handler.handle(event)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getNotificationType())
                    .isEqualTo(ru.taska.domain.NotificationType.ISSUE_COMMENT_CREATED);
            assertThat(all.get(0).getSourceEventId()).isEqualTo(eventId);
            assertThat(all.get(0).getUserId()).isEqualTo(userA);
            assertThat(all.get(0).getCreatedAt()).isNotNull();
            assertThat(all.get(0).getReadAt()).isNull();
        }

        @Test
        @DisplayName("findAllBySourceEventId возвращает только уведомления этого события")
        void shouldFindBySourceEventId() {
            // Событие 1
            UUID eventId1 = UUID.randomUUID();
            ObjectNode payload1 = basePayload();
            payload1.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload1.put(FIELD_BODY, "hi");
            setWatchers(payload1, userA);

            TaskaEvent event1 = TaskaEvent.builder()
                    .id(eventId1)
                    .aggregateType(AGGREGATE_TYPE_ISSUE)
                    .aggregateId(issueId)
                    .eventType(EventType.ISSUE_COMMENT_CREATED.getValue())
                    .payload(payload1)
                    .occurredAt(Instant.now())
                    .build();

            // Событие 2
            UUID eventId2 = UUID.randomUUID();
            ObjectNode payload2 = basePayload();
            payload2.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload2.put(FIELD_BODY, "hi");
            setWatchers(payload2, userB);

            TaskaEvent event2 = TaskaEvent.builder()
                    .id(eventId2)
                    .aggregateType(AGGREGATE_TYPE_ISSUE)
                    .aggregateId(issueId)
                    .eventType(EventType.ISSUE_COMMENT_CREATED.getValue())
                    .payload(payload2)
                    .occurredAt(Instant.now())
                    .build();

            StepVerifier.create(handler.handle(event1)).verifyComplete();
            StepVerifier.create(handler.handle(event2)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);

            List<Notification> byEvent1 = notificationRepository
                    .findAllBySourceEventId(eventId1)
                    .collectList()
                    .block();

            assertThat(byEvent1).hasSize(1);
            assertThat(byEvent1.get(0).getUserId()).isEqualTo(userA);
        }

        @Test
        @DisplayName("Два разных события с одним получателем создают два уведомления")
        void shouldCreateTwoNotificationsForTwoEvents() {
            UUID eventId1 = UUID.randomUUID();
            UUID eventId2 = UUID.randomUUID();

            ObjectNode payload = basePayload();
            payload.put(FIELD_AUTHOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hi");
            setWatchers(payload, userA);

            TaskaEvent event1 = TaskaEvent.builder()
                    .id(eventId1)
                    .aggregateType(AGGREGATE_TYPE_ISSUE)
                    .aggregateId(issueId)
                    .eventType(EventType.ISSUE_COMMENT_CREATED.getValue())
                    .payload(payload)
                    .occurredAt(Instant.now())
                    .build();

            TaskaEvent event2 = TaskaEvent.builder()
                    .id(eventId2)
                    .aggregateType(AGGREGATE_TYPE_ISSUE)
                    .aggregateId(issueId)
                    .eventType(EventType.ISSUE_COMMENT_CREATED.getValue())
                    .payload(payload)
                    .occurredAt(Instant.now())
                    .build();

            StepVerifier.create(handler.handle(event1)).verifyComplete();
            StepVerifier.create(handler.handle(event2)).verifyComplete();

            List<Notification> all = notificationRepository.findAll().collectList().block();
            assertThat(all).hasSize(2);  // два разных уведомления
            assertThat(all).extracting(Notification::getSourceEventId)
                    .containsExactlyInAnyOrder(eventId1, eventId2);
        }
    }

    @Test
    @DisplayName("ISSUE_ATTACHMENT_ADDED уведомляет наблюдателей, исключая загрузившего")
    void shouldNotifyWatchersExceptUploaderOnAttachmentAdded() {
        ObjectNode payload = basePayload();
        payload.put("uploadedBy", actorId.toString());
        payload.put("fileName", "report.pdf");
        setWatchers(payload, userA, userB, actorId);

        TaskaEvent event = event(EventType.ISSUE_ATTACHMENT_ADDED, payload);

        StepVerifier.create(handler.handle(event)).verifyComplete();

        List<Notification> all = notificationRepository.findAll().collectList().block();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(userA, userB)
                .doesNotContain(actorId);
    }

    @Test
    @DisplayName("ISSUE_ATTACHMENT_DELETED уведомляет наблюдателей, исключая удалившего")
    void shouldNotifyWatchersExceptDeleterOnAttachmentDeleted() {
        ObjectNode payload = basePayload();
        payload.put("deletedBy", actorId.toString());
        payload.put("fileName", "report.pdf");
        setWatchers(payload, userA, actorId);

        TaskaEvent event = event(EventType.ISSUE_ATTACHMENT_DELETED, payload);

        StepVerifier.create(handler.handle(event)).verifyComplete();

        List<Notification> all = notificationRepository.findAll().collectList().block();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getUserId()).isEqualTo(userA);
    }

    // ==================== Helpers ====================

    private ObjectNode basePayload() {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put(FIELD_ISSUE_ID, issueId.toString());
        return payload;
    }

    private ObjectNode linkPayload() {
        ObjectNode payload = basePayload();
        payload.put(FIELD_SOURCE_ISSUE_ID, UUID.randomUUID().toString());
        payload.put(FIELD_TARGET_ISSUE_ID, UUID.randomUUID().toString());
        payload.put(FIELD_LINK_TYPE, LINK_TYPE_BLOCKS);
        return payload;
    }

    private void setWatchers(ObjectNode payload, UUID... ids) {
        ArrayNode arr = OBJECT_MAPPER.createArrayNode();
        for (UUID id : ids) {
            arr.add(id.toString());
        }
        payload.set(FIELD_WATCHER_IDS, arr);
    }

    private TaskaEvent event(EventType type, JsonNode payload) {
        return TaskaEvent.builder()
                .id(eventId)
                .aggregateType(AGGREGATE_TYPE_ISSUE)
                .aggregateId(issueId)
                .eventType(type.getValue())
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
    }
}