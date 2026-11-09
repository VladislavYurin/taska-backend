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
import reactor.test.StepVerifier;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.event.OutboxEventStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.service.impl.OutboxEventServiceImpl;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceImplTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String REQUEST_ID = "test-request-id";
    private static final String NODE_ID = "test-node-id";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ru.taska.repository.ProjectRepository projectRepository;

    private OutboxEventServiceImpl outboxEventService;
    private VerifiedTenantContext tenantContext;

    @BeforeEach
    void setUp() {
        outboxEventService = new OutboxEventServiceImpl(outboxEventRepository, new ObjectMapper(), projectRepository);
        tenantContext = Mockito.mock(VerifiedTenantContext.class);
        Mockito.lenient().when(tenantContext.tenantId()).thenReturn(TENANT_ID.toString());
        Mockito.lenient().when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(project()));
        Mockito.lenient().when(outboxEventRepository.save(ArgumentMatchers.any()))
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

    @Test
    void roleChangesIncludeProjectKeyInStoredEvent() {
        withTenant(outboxEventService.saveMemberUpdated(REQUEST_ID, NODE_ID, USER_ID, ProjectRole.ADMIN, PROJECT_ID)).block();
        Assertions.assertThat(capture().getPayload().get("projectKey").asString()).isEqualTo("ABC");
    }

    @Test
    void missingProjectDoesNotPreventRemovalEvent() {
        Mockito.when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.empty());
        withTenant(outboxEventService.saveMemberRemoved(REQUEST_ID, NODE_ID, USER_ID, PROJECT_ID)).block();
        Assertions.assertThat(capture().getPayload().get("projectKey").asString()).isEmpty();
    }

    private OutboxEvent capture() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private Mono<OutboxEvent> withTenant(Mono<OutboxEvent> event) {
        return event.contextWrite(context -> context.put(VerifiedTenantContext.class, tenantContext));
    }

    @Test
    void rejectsEventBeforeSaveWhenVerifiedTenantIsMissing() {
        StepVerifier.create(outboxEventService.saveProjectCreated(REQUEST_ID, NODE_ID, project()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Verified context required"))
                .verify();

        Mockito.verifyNoInteractions(outboxEventRepository);
    }

    @Nested
    class SaveProjectCreated {

        @Test
        void shouldBuildProjectCreatedEvent() {
            withTenant(outboxEventService.saveProjectCreated(REQUEST_ID, NODE_ID, project())).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("ProjectCreated");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            Assertions.assertThat(event.getAttempts()).isEqualTo(0);
            Assertions.assertThat(event.getPayload()).isNotNull();
            Assertions.assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
            Assertions.assertThat(event.getPayload().get("projectKey").asString()).isEqualTo("ABC");
        }
    }

    @Nested
    class SaveMemberAdded {

        @Test
        void shouldBuildMemberAddedEvent() {
            withTenant(outboxEventService.saveMemberAdded(REQUEST_ID, NODE_ID, member())).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("MemberAdded");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            Assertions.assertThat(event.getAttempts()).isEqualTo(0);
            Assertions.assertThat(event.getPayload()).isNotNull();
            Assertions.assertThat(event.getPayload().get("projectKey").asString()).isEqualTo("ABC");
        }
    }

    @Nested
    class SaveMemberRemoved {

        @Test
        void shouldBuildMemberRemovedEvent() {
            withTenant(outboxEventService.saveMemberRemoved(REQUEST_ID, NODE_ID, member().getUserId(), member().getProjectId())).block();

            OutboxEvent event = capture();
            Assertions.assertThat(event.getAggregateType()).isEqualTo("project");
            Assertions.assertThat(event.getEventType()).isEqualTo("MemberRemoved");
            Assertions.assertThat(event.getPayload().get("projectKey").asString()).isEqualTo("ABC");
            Assertions.assertThat(event.getAggregateId()).isEqualTo(PROJECT_ID);
            Assertions.assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        }
    }
}
