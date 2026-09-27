package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationFactory: создание уведомлений по событиям")
class NotificationFactoryTest {

    // ==================== Поля payload ====================

    private static final String FIELD_ISSUE_ID = "issueId";
    private static final String FIELD_ACTOR_USER_ID = "actorUserId";
    private static final String FIELD_ASSIGNEE_ID = "assigneeId";
    private static final String FIELD_REPORTER_ID = "reporterId";
    private static final String FIELD_CREATED_BY = "createdBy";
    private static final String FIELD_DELETED_BY = "deletedBy";
    private static final String FIELD_SOURCE_ISSUE_ID = "sourceIssueId";
    private static final String FIELD_TARGET_ISSUE_ID = "targetIssueId";
    private static final String FIELD_LINK_TYPE = "linkType";
    private static final String FIELD_WATCHER_IDS = "watcherIds";
    private static final String FIELD_BODY = "body";
    private static final String FIELD_UPLOADED_BY = "uploadedBy";
    private static final String FIELD_FILE_NAME = "fileName";
    private static final String LINK_TYPE_BLOCKS = "BLOCKS";

    @Mock
    private NotificationMapper notificationMapper;

    @InjectMocks
    private NotificationFactory notificationFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID eventId;
    private UUID issueId;
    private UUID actorId;
    private UUID userA;
    private UUID userB;
    private UUID userC;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        issueId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        userC = UUID.randomUUID();

        // По умолчанию: любой вызов mapper возвращает Notification с userId из аргумента
        Mockito.lenient().when(notificationMapper.toIssueAssigned(any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_ASSIGNED));
        Mockito.lenient().when(notificationMapper.toIssueTransitioned(any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_TRANSITIONED));
        Mockito.lenient().when(notificationMapper.toIssueUpdated(any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_UPDATED));
        Mockito.lenient().when(notificationMapper.toIssueCreated(any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_CREATED));
        Mockito.lenient().when(notificationMapper.toIssueDeleted(any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_DELETED));
        Mockito.lenient().when(notificationMapper.toCommentCreated(any(), any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_COMMENT_CREATED));
        Mockito.lenient().when(notificationMapper.toIssueLinkCreated(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_LINK_CREATED));
        Mockito.lenient().when(notificationMapper.toIssueLinkDeleted(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_LINK_DELETED));
        Mockito.lenient().when(notificationMapper.toAttachmentAdded(any(), any(), any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_ATTACHMENT_ADDED));
        Mockito.lenient().when(notificationMapper.toAttachmentDeleted(any(), any(), any(), any()))
                .thenAnswer(inv -> notificationFor(inv.getArgument(1), NotificationType.ISSUE_ATTACHMENT_DELETED));
    }

    // ==================== ISSUE_COMMENT_CREATED ====================

    @Nested
    @DisplayName("ISSUE_COMMENT_CREATED")
    class CommentCreatedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей, исключая автора комментария")
        void shouldNotifyWatchersExceptAuthor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Возвращает пустой список, если наблюдателей нет")
        void shouldReturnEmptyOnEmptyWatchers() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список, если поле watcherIds отсутствует")
        void shouldReturnEmptyWhenWatcherIdsMissing() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список, если actorUserId отсутствует")
        void shouldReturnEmptyWhenAuthorMissing() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_ACTOR_USER_ID);
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Убирает дубликаты среди наблюдателей")
        void shouldDeduplicateWatchers() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA, userA, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Пропускает некорректный UUID в watcherIds без падения")
        void shouldSkipInvalidUuid() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            ArrayNode watchers = objectMapper.createArrayNode();
            watchers.add(userA.toString());
            watchers.add("not-a-uuid");
            watchers.add(userB.toString());
            payload.set(FIELD_WATCHER_IDS, watchers);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Возвращает пустой список, если автор — единственный наблюдатель")
        void shouldReturnEmptyWhenAuthorIsOnlyWatcher() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Создаёт уведомление ISSUE_COMMENT_CREATED с правильным sourceEventId")
        void shouldHaveCorrectTypeAndSourceEventId() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_COMMENT_CREATED);
            assertThat(result.get(0).getSourceEventId()).isEqualTo(eventId);
        }
    }

    // ==================== Типы уведомлений ====================

    @Nested
    @DisplayName("Типы уведомлений")
    class NotificationTypesTests {

        @Test
        @DisplayName("ISSUE_COMMENT_CREATED создаёт уведомление ISSUE_COMMENT_CREATED")
        void shouldHaveCorrectTypeForCommentCreated() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_COMMENT_CREATED);
        }

        @Test
        @DisplayName("ISSUE_TRANSITIONED создаёт уведомление ISSUE_TRANSITIONED")
        void shouldHaveCorrectTypeForTransitioned() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_TRANSITIONED);
        }

        @Test
        @DisplayName("ISSUE_ASSIGNED создаёт уведомление ISSUE_ASSIGNED")
        void shouldHaveCorrectTypeForAssigned() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_ASSIGNED);
        }

        @Test
        @DisplayName("ISSUE_UPDATED создаёт уведомление ISSUE_UPDATED")
        void shouldHaveCorrectTypeForUpdated() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_UPDATED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_UPDATED);
        }

        @Test
        @DisplayName("ISSUE_LINK_CREATED создаёт уведомление ISSUE_LINK_CREATED")
        void shouldHaveCorrectTypeForLinkCreated() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_CREATED_BY, actorId.toString());
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_CREATED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_LINK_CREATED);
        }

        @Test
        @DisplayName("ISSUE_LINK_DELETED создаёт уведомление ISSUE_LINK_DELETED")
        void shouldHaveCorrectTypeForLinkDeleted() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_DELETED_BY, actorId.toString());
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_DELETED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_LINK_DELETED);
        }

        @Test
        @DisplayName("Ни один получатель не получает дубликат уведомления")
        void shouldNotHaveDuplicateUserIds() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userA, userA, userB, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertThat(result).extracting(Notification::getUserId)
                    .doesNotHaveDuplicates();
        }
    }

    // ==================== ISSUE_TRANSITIONED ====================

    @Nested
    @DisplayName("ISSUE_TRANSITIONED")
    class TransitionedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей и исполнителя, исключая актора")
        void shouldNotifyWatchersAndAssigneeExceptActor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Не дублирует исполнителя, если он же наблюдатель")
        void shouldNotDuplicateAssigneeAndWatcher() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertUserIds(result, userA);
        }

        @Test
        @DisplayName("Возвращает пустой список, если единственный получатель — актор")
        void shouldReturnEmptyWhenOnlyActor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.putNull(FIELD_ASSIGNEE_ID);
            setWatcherIds(payload, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список, если в payload нет получателей")
        void shouldReturnEmptyOnEmptyPayload() {
            ObjectNode payload = createBasePayload();

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Уведомляет всех наблюдателей, если актор отсутствует")
        void shouldNotifyAllWhenActorNull() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_ACTOR_USER_ID);
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertUserIds(result, userA, userB);
        }
    }

    // ==================== ISSUE_ASSIGNED ====================

    @Nested
    @DisplayName("ISSUE_ASSIGNED")
    class AssignedTests {

        @Test
        @DisplayName("Уведомляет нового исполнителя и наблюдателей, исключая актора")
        void shouldNotifyAssigneeAndWatchers() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userB, userC);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertUserIds(result, userA, userB, userC);
        }

        @Test
        @DisplayName("Возвращает пустой список, если assigneeId отсутствует")
        void shouldReturnEmptyWhenAssigneeMissing() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.putNull(FIELD_ASSIGNEE_ID);
            setWatcherIds(payload, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Не уведомляет актора, если он сам назначил себя")
        void shouldNotNotifyActorIfSelfAssigned() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, actorId.toString());
            setWatcherIds(payload, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Уведомляет наблюдателей, если исполнитель — это актор")
        void shouldNotifyWatchersWhenAssigneeIsActor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, actorId.toString());
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Убирает дубликат исполнителя, если он же в наблюдателях")
        void shouldDedupAssigneeInWatchers() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userA, userA, userB, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Создаёт уведомление ISSUE_ASSIGNED")
        void shouldHaveCorrectType() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ASSIGNED, payload), eventId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotificationType())
                    .isEqualTo(NotificationType.ISSUE_ASSIGNED);
        }
    }

    // ==================== ISSUE_UPDATED ====================

    @Nested
    @DisplayName("ISSUE_UPDATED")
    class UpdatedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей и исполнителя, исключая актора")
        void shouldNotifyWatchersAndAssigneeExceptActor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            setWatcherIds(payload, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_UPDATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Уведомляет всех наблюдателей, если актор отсутствует")
        void shouldNotifyAllWhenActorNull() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_ACTOR_USER_ID);
            payload.putNull(FIELD_ASSIGNEE_ID);
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_UPDATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Возвращает пустой список, если получателей нет")
        void shouldReturnEmptyWhenNothingToNotify() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_ACTOR_USER_ID);
            payload.putNull(FIELD_ASSIGNEE_ID);
            setWatcherIds(payload);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_UPDATED, payload), eventId);

            assertThat(result).isEmpty();
        }
    }

    // ==================== ISSUE_LINK_CREATED ====================

    @Nested
    @DisplayName("ISSUE_LINK_CREATED")
    class LinkCreatedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей обеих задач, исключая актора")
        void shouldNotifyWatchersExceptActor() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_CREATED_BY, actorId.toString());
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Уведомляет всех наблюдателей, если createdBy отсутствует")
        void shouldNotifyAllWatchersWhenActorNull() {
            ObjectNode payload = createLinkPayload();
            payload.putNull(FIELD_CREATED_BY);
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Возвращает пустой список, если наблюдателей нет")
        void shouldReturnEmptyWhenWatchersEmpty() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_CREATED_BY, actorId.toString());
            setWatcherIds(payload);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Убирает дубликаты наблюдателей, попавших в обе задачи")
        void shouldDeduplicateWatchersBetweenIssues() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_CREATED_BY, actorId.toString());
            setWatcherIds(payload, userA, userA, userB, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }
    }

    // ==================== ISSUE_LINK_DELETED ====================

    @Nested
    @DisplayName("ISSUE_LINK_DELETED")
    class LinkDeletedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей, исключая актора")
        void shouldNotifyWatchersExceptActor() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_DELETED_BY, actorId.toString());
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_DELETED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Уведомляет всех наблюдателей, если deletedBy отсутствует")
        void shouldNotifyAllWatchersWhenActorNull() {
            ObjectNode payload = createLinkPayload();
            payload.putNull(FIELD_DELETED_BY);
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_DELETED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Возвращает пустой список, если наблюдателей нет")
        void shouldReturnEmptyWhenWatchersEmpty() {
            ObjectNode payload = createLinkPayload();
            payload.put(FIELD_DELETED_BY, actorId.toString());
            setWatcherIds(payload);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_LINK_DELETED, payload), eventId);

            assertThat(result).isEmpty();
        }
    }

    // ==================== ISSUE_CREATED ====================

    @Nested
    @DisplayName("ISSUE_CREATED")
    class IssueCreatedTests {

        @Test
        @DisplayName("Уведомляет автора и исполнителя, если они разные")
        void shouldNotifyReporterAndAssignee() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_REPORTER_ID, userA.toString());
            payload.put(FIELD_ASSIGNEE_ID, userB.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_CREATED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Убирает дубликат уведомления, если автор совпадает с исполнителем")
        void shouldDeduplicateReporterAssignee() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_REPORTER_ID, userA.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_CREATED, payload), eventId);

            assertUserIds(result, userA);
        }

        @Test
        @DisplayName("Уведомляет только автора, если исполнитель отсутствует")
        void shouldNotifyOnlyReporter() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_REPORTER_ID, userA.toString());
            payload.putNull(FIELD_ASSIGNEE_ID);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_CREATED, payload), eventId);

            assertUserIds(result, userA);
        }

        @Test
        @DisplayName("Возвращает пустой список, если оба поля null")
        void shouldReturnEmptyWhenBothNull() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_REPORTER_ID);
            payload.putNull(FIELD_ASSIGNEE_ID);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_CREATED, payload), eventId);

            assertThat(result).isEmpty();
        }
    }

    // ==================== ISSUE_DELETED ====================

    @Nested
    @DisplayName("ISSUE_DELETED")
    class IssueDeletedTests {

        @Test
        @DisplayName("Уведомляет исполнителя, исключая актора")
        void shouldNotifyAssigneeExceptActor() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_DELETED, payload), eventId);

            assertUserIds(result, userA);
        }

        @Test
        @DisplayName("Возвращает пустой список, если актор совпадает с исполнителем")
        void shouldReturnEmptyWhenActorIsAssignee() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, actorId.toString());

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_DELETED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список, если оба поля null")
        void shouldReturnEmptyWhenBothNull() {
            ObjectNode payload = createBasePayload();
            payload.putNull(FIELD_ACTOR_USER_ID);
            payload.putNull(FIELD_ASSIGNEE_ID);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_DELETED, payload), eventId);

            assertThat(result).isEmpty();
        }
    }

    // ==================== Общие крайние случаи ====================

    @Nested
    @DisplayName("Крайние случаи")
    class EdgeCases {

        @Test
        @DisplayName("Возвращает пустой список для неподдерживаемого типа события")
        void shouldReturnEmptyForUnsupportedEvent() {
            ObjectNode payload = createBasePayload();

            List<Notification> result = notificationFactory.create(
                    event(EventType.UNSUPPORTED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Не падает на пустом payload")
        void shouldNotThrowOnEmptyPayload() {
            ObjectNode payload = objectMapper.createObjectNode();

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Не падает, если watcherIds — не массив")
        void shouldNotThrowWhenWatcherIdsIsNotArray() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_ASSIGNEE_ID, userA.toString());
            payload.put(FIELD_WATCHER_IDS, "not-an-array");

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertUserIds(result, userA);
        }

        @Test
        @DisplayName("Не падает на null внутри watcherIds")
        void shouldNotThrowOnNullInWatcherIds() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            ArrayNode watchers = objectMapper.createArrayNode();
            watchers.add(userA.toString());
            watchers.addNull();
            watchers.add(userB.toString());
            payload.set(FIELD_WATCHER_IDS, watchers);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_TRANSITIONED, payload), eventId);

            assertUserIds(result, userA, userB);
        }

        @Test
        @DisplayName("Все созданные уведомления имеют sourceEventId = event.id()")
        void allNotificationsHaveSourceEventId() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_ACTOR_USER_ID, actorId.toString());
            payload.put(FIELD_BODY, "hello");
            setWatcherIds(payload, userA, userB);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_COMMENT_CREATED, payload), eventId);

            assertThat(result)
                    .isNotEmpty()
                    .allMatch(n -> eventId.equals(n.getSourceEventId()));
        }
    }

    @Nested
    @DisplayName("ISSUE_ATTACHMENT_ADDED")
    class AttachmentAddedTests {

        @Test
        @DisplayName("Уведомляет наблюдателей, исключая загрузившего (uploadedBy)")
        void shouldNotifyWatchersExceptUploader() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_UPLOADED_BY, actorId.toString());
            payload.put(FIELD_FILE_NAME, "report.pdf");
            setWatcherIds(payload, userA, userB, actorId);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ATTACHMENT_ADDED, payload), eventId);

            assertUserIds(result, userA, userB);
            assertThat(result).allMatch(n ->
                    n.getNotificationType() == NotificationType.ISSUE_ATTACHMENT_ADDED);
        }

        @Test
        @DisplayName("Возвращает пустой список, если нет fileName")
        void shouldReturnEmptyWhenFileNameMissing() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_UPLOADED_BY, actorId.toString());
            setWatcherIds(payload, userA);

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ATTACHMENT_ADDED, payload), eventId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Возвращает пустой список, если нет watcherIds")
        void shouldReturnEmptyWhenNoWatchers() {
            ObjectNode payload = createBasePayload();
            payload.put(FIELD_UPLOADED_BY, actorId.toString());
            payload.put(FIELD_FILE_NAME, "report.pdf");

            List<Notification> result = notificationFactory.create(
                    event(EventType.ISSUE_ATTACHMENT_ADDED, payload), eventId);

            assertThat(result).isEmpty();
        }
    }

    // ==================== Helpers ====================

    private ObjectNode createBasePayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put(FIELD_ISSUE_ID, issueId.toString());
        return payload;
    }

    private ObjectNode createLinkPayload() {
        ObjectNode payload = createBasePayload();
        payload.put(FIELD_SOURCE_ISSUE_ID, UUID.randomUUID().toString());
        payload.put(FIELD_TARGET_ISSUE_ID, UUID.randomUUID().toString());
        payload.put(FIELD_LINK_TYPE, LINK_TYPE_BLOCKS);
        return payload;
    }

    private void setWatcherIds(ObjectNode payload, UUID... ids) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (UUID id : ids) {
            arr.add(id.toString());
        }
        payload.set(FIELD_WATCHER_IDS, arr);
    }

    private TaskaEvent event(EventType type, ObjectNode payload) {
        return TaskaEvent.builder()
                .id(eventId)
                .aggregateId(issueId)
                .eventType(type.getValue())
                .payload(payload)
                .occurredAt(Instant.now())
                .build();
    }

    private Notification notificationFor(UUID userId, NotificationType type) {
        return Notification.builder()
                .userId(userId)
                .notificationType(type)
                .sourceEventId(eventId)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Проверяет, что в результате ровно ожидаемые userId и никаких других.
     */
    private void assertUserIds(List<Notification> result, UUID... expectedUserIds) {
        assertThat(result).hasSize(expectedUserIds.length);
        assertThat(result).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(expectedUserIds);
    }
}