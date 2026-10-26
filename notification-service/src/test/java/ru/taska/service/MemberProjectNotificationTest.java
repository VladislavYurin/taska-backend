package ru.taska.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.taska.event.TaskaEvent;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MemberProjectNotificationTest {
    private final NotificationMapper mapper = new NotificationMapper(null);
    private final NotificationFactory factory = new NotificationFactory(mapper);
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"MemberAdded", "MemberUpdated"})
    void newMembershipNotificationsCarryProjectThroughPublicResponse(String type) {
        UUID project = UUID.randomUUID(), user = UUID.randomUUID(), eventId = UUID.randomUUID();
        var payload = json.createObjectNode().put("projectId", project.toString())
                .put("userId", user.toString()).put("projectKey", "PROJ");
        var event = TaskaEvent.builder().id(eventId).aggregateType("project").aggregateId(UUID.randomUUID())
                .eventType(type).payload(payload).build();
        assertThat(factory.create(event, eventId)).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(user);
            assertThat(notification.getProjectId()).isEqualTo(project);
            assertThat(notification.getIssueId()).isNull();
            assertThat(mapper.toNotificationProto(notification).getProjectId()).isEqualTo(project.toString());
        });
    }

    @Test
    void removedMemberDoesNotReceiveAnOpenableProjectLink() {
        UUID project = UUID.randomUUID(), eventId = UUID.randomUUID();
        var event = TaskaEvent.builder().id(eventId).aggregateType("project").aggregateId(project)
                .eventType("MemberRemoved").payload(json.createObjectNode().put("projectId", project.toString())
                        .put("userId", UUID.randomUUID().toString())).build();
        assertThat(factory.create(event, eventId)).singleElement().satisfies(notification ->
                assertThat(notification.getProjectId()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"projectId\":\"malformed\"}"})
    void legacyProjectEventsUseProjectAggregateWithoutGuessingOtherAggregates(String payload) {
        UUID project = UUID.randomUUID();
        var event = TaskaEvent.builder().aggregateType("project").aggregateId(project).payload(json.readTree(payload)).build();
        assertThat(mapper.toMemberAdded(event, UUID.randomUUID()).getProjectId()).isEqualTo(project);
        var unrelated = TaskaEvent.builder().aggregateType("user").aggregateId(project).build();
        assertThat(mapper.toMemberUpdated(unrelated, UUID.randomUUID()).getProjectId()).isNull();
    }
}
