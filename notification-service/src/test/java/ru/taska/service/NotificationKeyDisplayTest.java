package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.taska.config.props.NotificationProperties;
import ru.taska.event.EventType;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.ObjectMapper;

class NotificationKeyDisplayTest {
    static Stream<Arguments> cases() {
        return Stream.of(EventType.PROJECT_CREATED, EventType.MEMBER_ADDED, EventType.MEMBER_UPDATED,
                EventType.MEMBER_REMOVED, EventType.ISSUE_CREATED, EventType.ISSUE_ASSIGNED,
                EventType.ISSUE_UPDATED, EventType.ISSUE_DELETED, EventType.ISSUE_TRANSITIONED,
                EventType.ISSUE_LINK_CREATED, EventType.ISSUE_LINK_DELETED, EventType.ISSUE_COMMENT_CREATED,
                EventType.ISSUE_LABEL_ADDED, EventType.ISSUE_LABEL_REMOVED,
                EventType.ISSUE_ATTACHMENT_ADDED, EventType.ISSUE_ATTACHMENT_DELETED)
                .flatMap(type -> Stream.of(Arguments.of(type, true), Arguments.of(type, false)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void currentAndLegacyEventsNeverDisplayEntityUuids(EventType type, boolean hasKeys) {
        var json = new ObjectMapper();
        var actor = UUID.randomUUID();
        var recipient = UUID.randomUUID();
        var issue = UUID.randomUUID();
        var target = UUID.randomUUID();
        var project = UUID.randomUUID();
        var payload = json.createObjectNode().put("issueId", issue.toString())
                .put("projectId", project.toString()).put("targetIssueId", target.toString())
                .put("actorUserId", actor.toString()).put("reporterId", actor.toString())
                .put("assigneeId", recipient.toString()).put("userId", recipient.toString())
                .put("createdBy", actor.toString()).put("deletedBy", actor.toString())
                .put("uploadedBy", actor.toString()).put("fileName", "file.txt")
                .put("labelName", "bug").put("linkType", "BLOCKS");
        payload.putArray("watcherIds").add(recipient.toString());
        if (hasKeys) payload.put("issueKey", "ABC-1").put("targetIssueKey", "ABC-2").put("projectKey", "ABC");
        var event = TaskaEvent.builder().id(UUID.randomUUID()).aggregateId(project)
                .eventType(type.getValue()).payload(payload).build();
        var mapper = new NotificationMapper(new NotificationProperties(new NotificationProperties.Comment(200)));
        var notifications = new NotificationFactory(mapper).create(event, event.id());
        assertThat(notifications).isNotEmpty().allSatisfy(notification -> {
            assertThat(notification.getTitle() + notification.getBody())
                    .doesNotContain(issue.toString(), target.toString(), project.toString());
            if (hasKeys) assertThat(notification.getBody()).contains("ABC");
            if (hasKeys && (type == EventType.ISSUE_LINK_CREATED || type == EventType.ISSUE_LINK_DELETED)) {
                assertThat(notification.getBody()).contains("ABC-1", "ABC-2");
            }
        });
    }
}
