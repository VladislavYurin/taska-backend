package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.ProjectService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class UpdateProjectIT extends AbstractIT {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "project-service";

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID NON_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private Project project;

    @BeforeEach
    void refillDb() {
        projectRepository.deleteAll().block();

        project = projectRepository.save(Project.builder()
                .projectKey("TEST")
                .name("Original name")
                .description("Original description")
                .color("#111111")
                .createdBy(ADMIN_ID)
                .build()).block();

        projectMemberRepository.save(buildMember(ADMIN_ID, ProjectRole.ADMIN)).block();
        projectMemberRepository.save(buildMember(MEMBER_ID, ProjectRole.MEMBER)).block();
        projectMemberRepository.save(buildMember(VIEWER_ID, ProjectRole.VIEWER)).block();
    }

    private ProjectMember buildMember(UUID userId, ProjectRole role) {
        return ProjectMember.builder()
                .projectId(project.getId())
                .userId(userId)
                .role(role)
                .addedBy(ADMIN_ID)
                .addedAt(Instant.now())
                .build();
    }

    @Test
    void adminUpdatesNameDescriptionAndColor_Success() {
        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), ADMIN_ID,
                Optional.of("New name"), Optional.of("New description"), Optional.of("#ABCDEF")
        )).assertNext(updated -> {
            Assertions.assertThat(updated.getName()).isEqualTo("New name");
            Assertions.assertThat(updated.getDescription()).isEqualTo("New description");
            Assertions.assertThat(updated.getColor()).isEqualTo("#ABCDEF");
        }).verifyComplete();

        StepVerifier.create(outboxEventRepository.findAll()
                .filter(event -> event.getAggregateId().equals(project.getId())
                        && event.getEventType().equals(EventType.PROJECT_UPDATED.getValue()))
                .collectList()
        ).assertNext(events -> Assertions.assertThat(events).hasSize(1))
                .verifyComplete();
    }

    @Test
    void adminSendsEmptyPatch_IsNoOp_DoesNotTouchUpdatedAtOrEmitEvent() {
        Instant updatedAtBeforePatch = project.getUpdatedAt();

        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), ADMIN_ID,
                Optional.empty(), Optional.empty(), Optional.empty()
        )).assertNext(updated -> {
            Assertions.assertThat(updated.getName()).isEqualTo("Original name");
            Assertions.assertThat(updated.getDescription()).isEqualTo("Original description");
            Assertions.assertThat(updated.getColor()).isEqualTo("#111111");
            Assertions.assertThat(updated.getUpdatedAt()).isEqualTo(updatedAtBeforePatch);
        }).verifyComplete();

        StepVerifier.create(outboxEventRepository.findAll()
                .filter(event -> event.getAggregateId().equals(project.getId())
                        && event.getEventType().equals(EventType.PROJECT_UPDATED.getValue()))
                .collectList()
        ).assertNext(events -> Assertions.assertThat(events).isEmpty())
                .verifyComplete();
    }

    @Test
    void patchOnlyNameField_DoesNotChangeDescriptionOrColor() {
        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), ADMIN_ID,
                Optional.of("Only name changed"), Optional.empty(), Optional.empty()
        )).assertNext(updated -> {
            Assertions.assertThat(updated.getName()).isEqualTo("Only name changed");
            Assertions.assertThat(updated.getDescription()).isEqualTo("Original description");
            Assertions.assertThat(updated.getColor()).isEqualTo("#111111");
        }).verifyComplete();
    }

    @Test
    void memberCannotUpdateProject_PermissionDenied() {
        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), MEMBER_ID,
                Optional.of("Hacked name"), Optional.empty(), Optional.empty()
        )).expectErrorSatisfies(this::assertPermissionDenied).verify();
    }

    @Test
    void viewerCannotUpdateProject_PermissionDenied() {
        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), VIEWER_ID,
                Optional.of("Hacked name"), Optional.empty(), Optional.empty()
        )).expectErrorSatisfies(this::assertPermissionDenied).verify();
    }

    @Test
    void nonMemberCannotUpdateProject_PermissionDenied() {
        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), NON_MEMBER_ID,
                Optional.of("Hacked name"), Optional.empty(), Optional.empty()
        )).expectErrorSatisfies(this::assertPermissionDenied).verify();
    }

    @Test
    void updateNonExistentProject_NotFound() {
        UUID nonExistentProjectId = UUID.randomUUID();

        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, nonExistentProjectId, ADMIN_ID,
                Optional.of("New name"), Optional.empty(), Optional.empty()
        )).expectErrorSatisfies(error -> {
            Assertions.assertThat(error).isInstanceOf(DomainException.class);
            Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
        }).verify();
    }

    @Test
    void adminUpdatesProject_VersionIsIncremented() {
        Integer versionBeforeUpdate = project.getVersion();

        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), ADMIN_ID,
                Optional.of("New name"), Optional.empty(), Optional.empty()
        )).assertNext(updated ->
                Assertions.assertThat(updated.getVersion()).isEqualTo(versionBeforeUpdate + 1)
        ).verifyComplete();
    }

    @Test
    void updateProjectWithStaleVersion_AbortedByOptimisticLock() {
        // Симулируем конкурентное обновление: "клиент A" читает проект (version=0),
        // затем "клиент B" успешно обновляет проект через сервис (version становится 1),
        // после чего "клиент A" пытается сохранить СВОЙ устаревший снимок (ещё version=0) —
        // это тот же read-modify-write, что и в реальном updateProject, только сведённый
        // к одному потоку ради детерминированности теста.
        Project staleSnapshot = project;

        StepVerifier.create(projectService.updateProject(
                REQUEST_ID, NODE_ID, project.getId(), ADMIN_ID,
                Optional.of("Updated by client B"), Optional.empty(), Optional.empty()
        )).assertNext(updated ->
                Assertions.assertThat(updated.getVersion()).isEqualTo(staleSnapshot.getVersion() + 1)
        ).verifyComplete();

        Project staleWrite = staleSnapshot.toBuilder()
                .name("Updated by client A, but stale")
                .build();

        StepVerifier.create(projectRepository.save(staleWrite))
                .expectErrorSatisfies(error ->
                        Assertions.assertThat(error).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
                )
                .verify();
    }

    @Test
    void createProjectWithDescriptionAndWithoutColor_GetReturnsNullColor() {
        UUID creatorId = UUID.randomUUID();

        StepVerifier.create(
                projectService.createProject(REQUEST_ID, NODE_ID, "NEWPROJ", "New project", creatorId,
                                Optional.of("Some description"), Optional.empty())
                        .flatMap(created -> projectService.getProject(REQUEST_ID, NODE_ID, created.getId(), creatorId))
        ).assertNext(fetched -> {
            Assertions.assertThat(fetched.getDescription()).isEqualTo("Some description");
            Assertions.assertThat(fetched.getColor()).isNull();
        }).verifyComplete();
    }

    private void assertPermissionDenied(Throwable error) {
        Assertions.assertThat(error).isInstanceOf(DomainException.class);
        Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
    }
}
