package ru.taska.service;

import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.ProjectSetting;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.impl.ProjectServiceImpl;
import ru.taska.service.validator.ProjectValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSettingRepository projectSettingRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectValidator projectValidator;

    @InjectMocks
    private ProjectServiceImpl projectService;

    private final String requestId = "req-123";
    private final String nodeId = "node-1";
    private final String projectKey = "PROJ";
    private final String projectName = "New Project";
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID actorUserId = UUID.randomUUID();

    private Project mockProject;

    @BeforeEach
    void setUp() {
        mockProject = Project.builder()
                .id(projectId)
                .projectKey(projectKey)
                .name(projectName)
                .createdBy(userId)
                .build();
    }

    @Test
    void createProject_Success() {
        Mockito.when(projectRepository.findByProjectKey(projectKey)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.save(ArgumentMatchers.any(Project.class))).thenReturn(Mono.just(mockProject));

        ObjectNode mockNode = Mockito.mock(ObjectNode.class);
        ArrayNode mockArray = Mockito.mock(ArrayNode.class);
        Mockito.when(objectMapper.createObjectNode()).thenReturn(mockNode);
        Mockito.when(mockNode.putArray("allowedIssueTypes")).thenReturn(mockArray);

        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(new ProjectMember()));
        Mockito.when(projectSettingRepository.save(ArgumentMatchers.any(ProjectSetting.class))).thenReturn(Mono.just(new ProjectSetting()));
        Mockito.when(outboxEventService.saveProjectCreated(ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(Project.class))).thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectService.createProject(requestId, nodeId, projectKey, projectName, userId))
                .expectNext(mockProject)
                .verifyComplete();

        Mockito.verify(projectRepository).findByProjectKey(projectKey);
        Mockito.verify(projectRepository).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(projectMemberRepository).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(projectSettingRepository).save(ArgumentMatchers.any(ProjectSetting.class));
        Mockito.verify(outboxEventService).saveProjectCreated(ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(Project.class));
    }

    @Test
    void createProject_ThrowsAlreadyExistsException_WhenProjectExists() {
        Mockito.when(projectRepository.findByProjectKey(projectKey)).thenReturn(Mono.just(mockProject));

        StepVerifier.create(projectService.createProject(requestId, nodeId, projectKey, projectName, userId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.ALREADY_EXISTS, exception.getStatus());
                    Assertions.assertEquals("Project with key " + projectKey + " already exists", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findByProjectKey(projectKey);
        Mockito.verify(projectRepository, Mockito.never()).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(projectMemberRepository, Mockito.never()).save(ArgumentMatchers.any(ProjectMember.class));
        Mockito.verify(projectSettingRepository, Mockito.never()).save(ArgumentMatchers.any(ProjectSetting.class));
    }

    @Test
    void getProject_Success() {
        ProjectCheckMembershipDto dto = ProjectCheckMembershipDto.builder()
                .project_id(projectId)
                .project_key(projectKey)
                .name(projectName)
                .created_by(userId)
                .created_at(null)
                .updated_at(null)
                .archived_at(null)
                .user_id(actorUserId)
                .build();

        Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                .thenReturn(Mono.just(dto)
                );
        Mockito.when(projectMapper.toProject(dto))
                .thenReturn(mockProject);

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId, actorUserId))
                .expectNext(mockProject)
                .verifyComplete();

        Mockito.verify(projectRepository).findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId);
        Mockito.verify(projectMapper).toProject(dto);
    }

    @Test
    void getProject_ThrowsNotFoundException_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId)).thenReturn(Mono.empty());

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId, actorUserId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project not found", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId);
        Mockito.verify(projectMapper, Mockito.never()).toProject(ArgumentMatchers.any());
    }

    @Test
    void getProject_ThrowsPermissionDenied_WhenUserIsNotMember() {
        ProjectCheckMembershipDto dto = ProjectCheckMembershipDto.builder()
                .project_id(projectId)
                .project_key(projectKey)
                .name(projectName)
                .created_by(userId)
                .created_at(null)
                .updated_at(null)
                .archived_at(null)
                .user_id(null)
                .build();

        Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId)).thenReturn(Mono.just(dto));

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId, actorUserId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                    Assertions.assertEquals("You don't have access to this project", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId);
        Mockito.verify(projectMapper, Mockito.never()).toProject(ArgumentMatchers.any());
    }

    @Test
    void listMyProjects_Success() {

        Mockito.when(projectRepository.findAllByMemberUserId(userId)).thenReturn(Flux.just(mockProject, mockProject));

        StepVerifier.create(projectService.listMyProjects(requestId, nodeId, userId))
                .expectNext(mockProject)
                .expectNext(mockProject)
                .verifyComplete();

        Mockito.verify(projectRepository).findAllByMemberUserId(userId);
    }

    @Test
    void listMyProjects_Success_EmptyList() {
        Mockito.when(projectRepository.findAllByMemberUserId(userId)).thenReturn(Flux.empty());

        StepVerifier.create(projectService.listMyProjects(requestId, nodeId, userId))
                .expectNextCount(0)
                .verifyComplete();

        Mockito.verify(projectRepository).findAllByMemberUserId(userId);
    }

    @Test
    void getProjectKeyByIdInternal_Success() {
        String expectedProjectKey = "TEST";
        Mockito.when(projectRepository.findProjectKeyById(projectId))
                .thenReturn(Mono.just(expectedProjectKey));

        StepVerifier.create(projectService.getProjectKeyByIdInternal(projectId))
                .expectNext(expectedProjectKey)
                .verifyComplete();

        Mockito.verify(projectRepository).findProjectKeyById(projectId);
    }

    @Test
    void getProjectKeyByIdInternal_ThrowsNotFoundException_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.findProjectKeyById(projectId))
                .thenReturn(Mono.empty());

        StepVerifier.create(projectService.getProjectKeyByIdInternal(projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertInstanceOf(DomainException.class, throwable);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project not found", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findProjectKeyById(projectId);
    }

    // ---------- softDeleteProject ----------

    @Test
    void softDeleteProject_Success_WhenNotArchived() {
        ProjectMember adminMember = ProjectMember.builder()
                                                 .userId(actorUserId)
                                                 .projectId(projectId)
                                                 .role(ProjectRole.ADMIN)
                                                 .build();

        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(actorUserId, projectId))
               .thenReturn(Mono.just(adminMember));
        Mockito.when(projectRepository.save(ArgumentMatchers.any(Project.class)))
               .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(outboxEventService.saveProjectArchived(
                       ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(Project.class)))
               .thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectService.softDeleteProject(requestId, nodeId, projectId, actorUserId))
                    .expectNextMatches(project -> project.getId().equals(projectId) && project.getArchivedAt() != null)
                    .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectRepository).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(outboxEventService).saveProjectArchived(
                ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(Project.class));
    }

    @Test
    void softDeleteProject_Success_WhenAlreadyArchived() {
        Project archivedProject = Project.builder()
                                         .id(projectId)
                                         .projectKey(projectKey)
                                         .name(projectName)
                                         .createdBy(userId)
                                         .archivedAt(Instant.now())
                                         .build();

        ProjectMember adminMember = ProjectMember.builder()
                                                 .userId(actorUserId)
                                                 .projectId(projectId)
                                                 .role(ProjectRole.ADMIN)
                                                 .build();

        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(archivedProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(actorUserId, projectId))
               .thenReturn(Mono.just(adminMember));

        StepVerifier.create(projectService.softDeleteProject(requestId, nodeId, projectId, actorUserId))
                    .expectNext(archivedProject)
                    .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectRepository, Mockito.never()).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(outboxEventService, Mockito.never()).saveProjectArchived(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void softDeleteProject_ThrowsNotFoundException_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.empty());

        StepVerifier.create(projectService.softDeleteProject(requestId, nodeId, projectId, actorUserId))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertTrue(throwable instanceof DomainException);
                        DomainException exception = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                        Assertions.assertEquals("Project not found", exception.getMessage());
                    })
                    .verify();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository, Mockito.never())
               .findByUserIdAndProjectId(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(projectRepository, Mockito.never()).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(outboxEventService, Mockito.never()).saveProjectArchived(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void softDeleteProject_ThrowsNotFoundException_WhenActorIsNotAMember() {
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(actorUserId, projectId))
               .thenReturn(Mono.empty());

        StepVerifier.create(projectService.softDeleteProject(requestId, nodeId, projectId, actorUserId))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertTrue(throwable instanceof DomainException);
                        DomainException exception = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                        Assertions.assertEquals("User not found", exception.getMessage());
                    })
                    .verify();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(actorUserId, projectId);
        Mockito.verify(projectRepository, Mockito.never()).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(outboxEventService, Mockito.never()).saveProjectArchived(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void softDeleteProject_ThrowsPermissionDenied_WhenActorIsNotAdmin() {
        ProjectMember viewerMember = ProjectMember.builder()
                                                  .userId(actorUserId)
                                                  .projectId(projectId)
                                                  .role(ProjectRole.VIEWER)
                                                  .build();

        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(actorUserId, projectId))
               .thenReturn(Mono.just(viewerMember));

        StepVerifier.create(projectService.softDeleteProject(requestId, nodeId, projectId, actorUserId))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertTrue(throwable instanceof DomainException);
                        DomainException exception = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                        Assertions.assertEquals("User must have the role ADMIN", exception.getMessage());
                    })
                    .verify();

        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(actorUserId, projectId);
        Mockito.verify(projectRepository, Mockito.never()).save(ArgumentMatchers.any(Project.class));
        Mockito.verify(outboxEventService, Mockito.never()).saveProjectArchived(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    // ---------- checkProjectAccess ----------

    @Test
    void checkProjectAccess_Success_WhenMemberAndNotArchived() {
        ProjectMember member = ProjectMember.builder()
                                            .userId(userId)
                                            .projectId(projectId)
                                            .role(ProjectRole.MEMBER)
                                            .build();

        Mockito.when(projectValidator.isArchived(requestId, nodeId, projectId)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
               .thenReturn(Mono.just(member));

        StepVerifier.create(projectService.checkProjectAccess(requestId, nodeId, projectId, userId))
                    .expectNextMatches(dto ->
                                               dto.role() == ProjectRole.MEMBER
                                                       && dto.isMember()
                                                       && dto.isProjectExists()
                                                       && !dto.isProjectArchived())
                    .verifyComplete();

        Mockito.verify(projectValidator).isArchived(requestId, nodeId, projectId);
        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(userId, projectId);
    }

    @Test
    void checkProjectAccess_Success_WhenProjectExistsButUserIsNotMember() {
        Mockito.when(projectValidator.isArchived(requestId, nodeId, projectId)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
               .thenReturn(Mono.empty());

        StepVerifier.create(projectService.checkProjectAccess(requestId, nodeId, projectId, userId))
                    .expectNextMatches(dto ->
                                               dto.role() == ProjectRole.UNSPECIFIED
                                                       && !dto.isMember()
                                                       && dto.isProjectExists()
                                                       && !dto.isProjectArchived())
                    .verifyComplete();

        Mockito.verify(projectValidator).isArchived(requestId, nodeId, projectId);
        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(userId, projectId);
    }

    @Test
    void checkProjectAccess_Success_WhenProjectDoesNotExist() {
        Mockito.when(projectValidator.isArchived(requestId, nodeId, projectId)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.empty());

        StepVerifier.create(projectService.checkProjectAccess(requestId, nodeId, projectId, userId))
                    .expectNextMatches(dto ->
                                               dto.role() == ProjectRole.UNSPECIFIED
                                                       && !dto.isMember()
                                                       && !dto.isProjectExists()
                                                       && !dto.isProjectArchived())
                    .verifyComplete();

        Mockito.verify(projectValidator).isArchived(requestId, nodeId, projectId);
        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository, Mockito.never())
               .findByUserIdAndProjectId(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void checkProjectAccess_Success_WhenProjectArchived() {
        ProjectMember adminMember = ProjectMember.builder()
                                                 .userId(userId)
                                                 .projectId(projectId)
                                                 .role(ProjectRole.ADMIN)
                                                 .build();

        Mockito.when(projectValidator.isArchived(requestId, nodeId, projectId))
               .thenReturn(Mono.error(new DomainException(DomainStatus.PROJECT_ARCHIVED, "Project has been archived")));
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMemberRepository.findByUserIdAndProjectId(userId, projectId))
               .thenReturn(Mono.just(adminMember));

        StepVerifier.create(projectService.checkProjectAccess(requestId, nodeId, projectId, userId))
                    .expectNextMatches(dto ->
                                               dto.role() == ProjectRole.ADMIN
                                                       && dto.isMember()
                                                       && dto.isProjectExists()
                                                       && dto.isProjectArchived())
                    .verifyComplete();

        Mockito.verify(projectValidator).isArchived(requestId, nodeId, projectId);
        Mockito.verify(projectRepository).findById(projectId);
        Mockito.verify(projectMemberRepository).findByUserIdAndProjectId(userId, projectId);
    }
}