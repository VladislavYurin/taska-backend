package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import ru.taska.config.props.NotificationProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.EmailDeliveryAttemptMapper;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.ObjectMapper;

class CommentNotificationPrivacyTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyStoredPreview_isRedactedOnInboxReadsAndEmailRetries(boolean hasIssueKey) {
        UUID issue = UUID.randomUUID();
        var notification = Notification.builder().notificationType(NotificationType.ISSUE_COMMENT_CREATED)
                .issueId(issue).issueKey(hasIssueKey ? "PRV-1" : null).body("secret legacy preview").build();
        var mapper = new NotificationMapper(new NotificationProperties(new NotificationProperties.Comment(200)));
        String expected = "Новый комментарий к задаче " + (hasIssueKey ? "PRV-1" : issue);

        assertThat(mapper.toNotificationProto(notification).getBody()).isEqualTo(expected);
        assertThat(new EmailDeliveryAttemptMapper().toMailMessage(notification, "watcher@example.com").getText())
                .isEqualTo(expected);
        assertThat(notification.getBody()).isEqualTo("secret legacy preview");
    }

    @Test
    void unrelatedNotification_keepsItsBody() {
        var notification = Notification.builder().notificationType(NotificationType.ISSUE_UPDATED)
                .body("preserved").build();
        assertThat(NotificationMapper.safeBody(notification)).isEqualTo("preserved");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyAndMetadataOnlyEvents_produceNoPreviewInInboxOrEmail(boolean legacyBody) {
        var json = new ObjectMapper();
        UUID actor = UUID.randomUUID();
        UUID watcher = UUID.randomUUID();
        UUID issue = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var payload = json.createObjectNode().put("actorUserId", actor.toString())
                .put("issueId", issue.toString()).put("projectId", UUID.randomUUID().toString())
                .put("issueKey", "PRV-1");
        payload.putArray("watcherIds").add(actor.toString()).add(watcher.toString());
        if (legacyBody) {
            payload.put("body", "secret deleted comment");
        }
        var factory = new NotificationFactory(new NotificationMapper(
                new NotificationProperties(new NotificationProperties.Comment(200))));
        var event = TaskaEvent.builder().id(eventId).aggregateId(issue).aggregateType("ISSUE")
                .eventType(EventType.ISSUE_COMMENT_CREATED.getValue()).payload(payload).build();

        var notifications = factory.create(event, eventId);

        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(watcher);
            assertThat(notification.getBody()).isEqualTo("Новый комментарий к задаче PRV-1");
            assertThat(new EmailDeliveryAttemptMapper().toMailMessage(notification, "watcher@example.com").getText())
                    .isEqualTo(notification.getBody());
        });
    }
}
