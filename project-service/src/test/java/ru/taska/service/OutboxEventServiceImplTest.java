package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.event.OutboxEventStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.impl.OutboxEventServiceImpl;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceImplTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String REQUEST_ID = "test-request-id";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventServiceImpl outboxEventService;

    @BeforeEach
    void setUp() {
        outboxEventService = new OutboxEventServiceImpl(outboxEventRepository, new ObjectMapper());
        Mockito.when(outboxEventRepository.save(ArgumentMatchers.any()))
               .thenAnswer(invocation -> Mono.just((OutboxEvent) invocation.getArgument(0)));
    }

    private Project project() {
        return Project.builder()
                      .id(PROJECT_ID)
                      .projectKey("ABC")
                      .name("Test Project")
                      .createdBy(USER_ID)
                      .build();
    }

    private ProjectMember member() {
        return ProjectMember.builder()
                            .projectId(PROJECT_ID)
                            .userId(USER_ID)
                            .role(ProjectRole.MEMBER)
                            .addedBy(USER_ID)
                            .build();
    }

    private OutboxEvent capture() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    class SaveProjectCreated {

        @Test
        void shouldBuildProjectCreatedEvent() {
            outboxEventService.saveProjectCreated(REQUEST_ID, project()).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("ProjectCreated");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            Assertions.assertThat(event.getAttempts()).isEqualTo(0);
            Assertions.assertThat(event.getPayload()).isNotNull();
        }
    }

    @Nested
    class SaveMemberAdded {

        @Test
        void shouldBuildMemberAddedEvent() {
            outboxEventService.saveMemberAdded(REQUEST_ID, member()).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("MemberAdded");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            Assertions.assertThat(event.getAttempts()).isEqualTo(0);
            Assertions.assertThat(event.getPayload()).isNotNull();
        }
    }

    @Nested
    class SaveMemberRemoved {

        @Test
        void shouldBuildMemberRemovedEvent() {
            outboxEventService.saveMemberRemoved(REQUEST_ID, member().getUserId(), member().getProjectId()).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("MemberRemoved");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        }
    }
}
