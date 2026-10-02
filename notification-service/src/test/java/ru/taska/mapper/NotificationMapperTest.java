package ru.taska.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.config.props.NotificationProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.IssueInfo;
import ru.taska.event.TaskaEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class NotificationMapperTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TARGET_ISSUE_ID = UUID.randomUUID();
    private static final UUID LINK_ID = UUID.randomUUID();
    private static final String ISSUE_KEY = "TEST-1";
    private static final String LINK_TYPE = "BLOCKS";

    private NotificationMapper mapper;
    private IssueInfo issueInfo;
    private TaskaEvent event;

    @BeforeEach
    void setUp() {
        NotificationProperties props = new NotificationProperties(
                new NotificationProperties.Comment(200));
        mapper = new NotificationMapper(props);
        issueInfo = new IssueInfo(ISSUE_ID, ISSUE_KEY, PROJECT_ID);
        event = TaskaEvent.builder()
                .id(EVENT_ID)
                .aggregateType("issue")
                .aggregateId(ISSUE_ID)
                .eventType("SomeEvent")
                .build();
    }

    // ==================== Регрессия: link-события ====================

    @Nested
    @DisplayName("Link events — регрессия из ревью TAS-184")
    class LinkEvents {

        @Test
        @DisplayName("toIssueLinkDeleted должен заполнять issueId/issueKey/projectId")
        void toIssueLinkDeleted_shouldFillIssueFields() {
            Notification result = mapper.toIssueLinkDeleted(
                    event, USER_ID, issueInfo, TARGET_ISSUE_ID, LINK_TYPE, LINK_ID);

            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
            assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_LINK_DELETED);
            assertThat(result.getBody()).contains(ISSUE_KEY);
        }

        @Test
        @DisplayName("toIssueLinkCreated должен заполнять issueId/issueKey/projectId (симметрия)")
        void toIssueLinkCreated_shouldFillIssueFields() {
            Notification result = mapper.toIssueLinkCreated(
                    event, USER_ID, issueInfo, TARGET_ISSUE_ID, LINK_TYPE, LINK_ID);

            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
            assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_LINK_CREATED);
        }
    }

    // ==================== Issue lifecycle ====================

    @Nested
    @DisplayName("Issue lifecycle events")
    class IssueLifecycle {

        @Test
        void toIssueCreated_shouldFillIssueFields() {
            Notification result = mapper.toIssueCreated(event, USER_ID, issueInfo);

            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_CREATED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
            assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
            assertThat(result.getSourceEventId()).isEqualTo(EVENT_ID);
        }

        @Test
        void toIssueAssigned_shouldFillIssueFields() {
            Notification result = mapper.toIssueAssigned(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_ASSIGNED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
            assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
        }

        @Test
        void toIssueTransitioned_shouldFillIssueFields() {
            Notification result = mapper.toIssueTransitioned(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_TRANSITIONED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
        }

        @Test
        void toIssueUpdated_shouldFillIssueFields() {
            Notification result = mapper.toIssueUpdated(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_UPDATED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
        }

        @Test
        void toIssueDeleted_shouldFillIssueFields() {
            Notification result = mapper.toIssueDeleted(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_DELETED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
        }
    }

    // ==================== Label & Comment ====================

    @Nested
    @DisplayName("Label and comment events")
    class LabelAndComment {

        @Test
        void toLabelAdded_shouldFillIssueFields() {
            Notification result = mapper.toLabelAdded(event, USER_ID, issueInfo, "bug");

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.LABEL_ADDED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
        }

        @Test
        void toLabelRemoved_shouldFillIssueFields() {
            Notification result = mapper.toLabelRemoved(event, USER_ID, issueInfo, "bug");

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.LABEL_REMOVED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
        }

        @Test
        void toCommentCreated_shouldFillIssueFields() {
            Notification result = mapper.toCommentCreated(event, USER_ID, issueInfo, "test body");

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_COMMENT_CREATED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
            assertThat(result.getIssueKey()).isEqualTo(ISSUE_KEY);
        }

        @Test
        void toCommentUpdated_shouldFillIssueFields() {
            Notification result = mapper.toCommentUpdated(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_COMMENT_UPDATED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
        }

        @Test
        void toCommentDeleted_shouldFillIssueFields() {
            Notification result = mapper.toCommentDeleted(event, USER_ID, issueInfo);

            assertThat(result.getNotificationType()).isEqualTo(NotificationType.ISSUE_COMMENT_DELETED);
            assertThat(result.getIssueId()).isEqualTo(ISSUE_ID);
        }
    }

    // ==================== Не-issue события ====================

    @Nested
    @DisplayName("Non-issue events — не должны заполнять issue-поля")
    class NonIssueEvents {

        @Test
        void toUserInvited_shouldNotFillIssueFields() {
            Notification result = mapper.toUserInvited(event);

            assertThat(result.getIssueId()).isNull();
            assertThat(result.getIssueKey()).isNull();
            assertThat(result.getProjectId()).isNull();
        }

        @Test
        void toProjectCreated_shouldNotFillIssueFields() {
            Notification result = mapper.toProjectCreated(event, USER_ID);

            assertThat(result.getIssueId()).isNull();
            assertThat(result.getIssueKey()).isNull();
            assertThat(result.getProjectId()).isNull();
        }

        @Test
        void toMemberAdded_shouldNotFillIssueFields() {
            Notification result = mapper.toMemberAdded(event, USER_ID);

            assertThat(result.getIssueId()).isNull();
            assertThat(result.getIssueKey()).isNull();
        }

        @Test
        void toUserBlocked_shouldNotFillIssueFields() {
            Notification result = mapper.toUserBlocked(event, USER_ID, "reason");

            assertThat(result.getIssueId()).isNull();
        }
    }

    // ==================== displayKey fallback ====================

    @Nested
    @DisplayName("displayKey fallback на aggregateId")
    class DisplayKeyFallback {

        @Test
        void shouldUseIssueKeyWhenPresent() {
            Notification result = mapper.toIssueCreated(event, USER_ID, issueInfo);

            assertThat(result.getBody()).contains(ISSUE_KEY);
            assertThat(result.getBody()).doesNotContain(ISSUE_ID.toString());
        }

        @Test
        void shouldFallbackToAggregateIdWhenIssueKeyMissing() {
            IssueInfo emptyRef = new IssueInfo(ISSUE_ID, null, PROJECT_ID);

            Notification result = mapper.toIssueCreated(event, USER_ID, emptyRef);

            // fallback вернёт aggregateId (= ISSUE_ID)
            assertThat(result.getBody()).contains(ISSUE_ID.toString());
        }

        @Test
        void shouldFallbackToAggregateIdWhenIssueKeyBlank() {
            IssueInfo blankKeyRef = new IssueInfo(ISSUE_ID, "   ", PROJECT_ID);

            Notification result = mapper.toIssueCreated(event, USER_ID, blankKeyRef);

            assertThat(result.getBody()).contains(ISSUE_ID.toString());
        }
    }

    // ==================== toNotificationProto ====================

    @Nested
    @DisplayName("toNotificationProto")
    class ToProto {

        @Test
        void shouldMapAllFields() {
            Notification notification = Notification.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .notificationType(NotificationType.ISSUE_ASSIGNED)
                    .title("Title")
                    .body("Body")
                    .issueId(ISSUE_ID)
                    .issueKey(ISSUE_KEY)
                    .projectId(PROJECT_ID)
                    .sourceEventId(EVENT_ID)
                    .build();

            NotificationResponse proto = mapper.toNotificationProto(notification);

            assertThat(proto.getIssueId()).isEqualTo(ISSUE_ID.toString());
            assertThat(proto.getIssueKey()).isEqualTo(ISSUE_KEY);
            assertThat(proto.getProjectId()).isEqualTo(PROJECT_ID.toString());
            assertThat(proto.getNotificationType()).isEqualTo(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED);
        }

        @Test
        void shouldReturnEmptyStringsForNullFields() {
            Notification notification = Notification.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .notificationType(NotificationType.USER_INVITED)
                    .title("Title")
                    .body("Body")
                    .sourceEventId(EVENT_ID)
                    // issueId/issueKey/projectId — null
                    .build();

            NotificationResponse proto = mapper.toNotificationProto(notification);

            assertThat(proto.getIssueId()).isEmpty();
            assertThat(proto.getIssueKey()).isEmpty();
            assertThat(proto.getProjectId()).isEmpty();
        }
    }
}