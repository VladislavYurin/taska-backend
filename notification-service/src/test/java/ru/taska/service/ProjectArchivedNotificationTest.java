package ru.taska.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.event.TaskaEvent;
import ru.taska.event.payload.projectService.ProjectArchivedPayload;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ProjectArchivedNotificationTest {
    @Test
    void membersReceiveProjectLinkAndCaptionOnceWithoutNotifyingActor() {
        UUID actor=UUID.randomUUID(),member=UUID.randomUUID(),project=UUID.randomUUID(),eventId=UUID.randomUUID();
        var mapper=new NotificationMapper(null);
        var event=TaskaEvent.builder().id(eventId).eventType("ProjectArchived").aggregateId(project)
                .payload(new ObjectMapper().valueToTree(new ProjectArchivedPayload(project,"BUS","Business project",actor,Instant.now(),List.of(actor,member,member)))).build();
        var result=new NotificationFactory(mapper).create(event,eventId);
        assertThat(result).singleElement().satisfies(n->{
            assertThat(n.getUserId()).isEqualTo(member);
            assertThat(n.getProjectId()).isEqualTo(project);
            assertThat(n.getBody()).contains("Business project").doesNotContain(project.toString());
            assertThat(mapper.toNotificationProto(n).getNotificationType()).isEqualTo(ru.taska.api.notification.v1.NotificationKind.NOTIFICATION_KIND_PROJECT_ARCHIVED);
        });
    }
}
