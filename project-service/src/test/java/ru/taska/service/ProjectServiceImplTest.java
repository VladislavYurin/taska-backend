package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.ProjectSetting;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectLabelDto;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowDto;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.impl.ProjectServiceImpl;
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;
import ru.taska.transport.grpc.client.GrpcWorkFlowServiceClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.util.List;
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

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private GrpcIssueServiceClient issueServiceClient;

    @Mock
    private GrpcWorkFlowServiceClient workflowServiceClient;

    @Mock
    private ProjectMemberService projectMemberService;

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
    private ProjectCheckMembershipDto projectCheckMembershipDto;

    @BeforeEach
    void setUp() {
        mockProject = Project.builder()
                .id(projectId)
                .projectKey(projectKey)
                .name(projectName)
                .createdBy(userId)
                .build();

        projectCheckMembershipDto = ProjectCheckMembershipDto.builder()
                .project_id(projectId)
                .project_key(projectKey)
                .role(ProjectRole.ADMIN)
                .name(projectName)
                .created_by(userId)
                .user_id(actorUserId)
                .build();
    }

    @Test
    void createProject_Success() {
        Mockito.when(projectRepository.findByProjectKey(projectKey)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.save(ArgumentMatchers.any(Project.class))).thenReturn(Mono.just(mockProject));

        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class)))
                .thenReturn(Mono.just(new ProjectMember()));
        Mockito.when(projectSettingRepository.insertSetting(
                        ArgumentMatchers.eq(projectId),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(Instant.class),
                        ArgumentMatchers.eq(userId)))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveProjectCreated(
                        ArgumentMatchers.eq(requestId), ArgumentMatchers.eq(nodeId), ArgumentMatchers.any(Project.class)))
                .thenReturn(Mono.just(new OutboxEvent()));

        StepVerifier.create(projectService.createProject(requestId, nodeId, projectKey, projectName, userId))
                .expectNext(mockProject)
                .verifyComplete();

        Mockito.verify(projectSettingRepository).insertSetting(
                ArgumentMatchers.eq(projectId),
                ArgumentMatchers.contains("allowedIssueTypes"),
                ArgumentMatchers.any(Instant.class),
                ArgumentMatchers.eq(userId));

        Mockito.verify(projectSettingRepository, Mockito.never()).save(ArgumentMatchers.any());
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
                .role(ProjectRole.ADMIN)
                .build();

        Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                .thenReturn(Mono.just(dto)
                );

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId, actorUserId))
                .expectNext(projectCheckMembershipDto)
                .verifyComplete();

        Mockito.verify(projectRepository).findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId);
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

        Mockito.when(projectRepository.findAllByMemberUserId(userId))
                .thenReturn(Flux.just(projectCheckMembershipDto, projectCheckMembershipDto));

        StepVerifier.create(projectService.listMyProjects(requestId, nodeId, userId))
                .expectNext(projectCheckMembershipDto)
                .expectNext(projectCheckMembershipDto)
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

    @Test
    void getProjectInfoByIds_ReturnsEmptyMap_WhenProjectsDoNotExistInDb() {
        // Arrange
        UUID projectId1 = UUID.randomUUID();
        UUID projectId2 = UUID.randomUUID();
        List<UUID> projectIds = List.of(projectId1, projectId2);

        Mockito.when(projectRepository.findProjectInfoByIds(Mockito.anyCollection()))
                .thenReturn(Flux.empty());

        // Act & Assert
        StepVerifier.create(projectService.getProjectInfoByIds(projectIds))
                .assertNext(resultMap -> {
                    Assertions.assertNotNull(resultMap);
                    Assertions.assertTrue(resultMap.isEmpty());
                })
                .verifyComplete();

        Mockito.verify(projectRepository).findProjectInfoByIds(Mockito.argThat(ids ->
                ids.containsAll(projectIds) && ids.size() == projectIds.size()
        ));
    }

    @Test
    void getProjectInfoByIds_ReturnsMapWithFoundProjects_WhenSomeProjectsExist() {
        // Arrange
        UUID projectId1 = UUID.randomUUID();
        UUID projectId2 = UUID.randomUUID();
        List<UUID> projectIds = List.of(projectId1, projectId2);

        ProjectInfo projectInfo1 = new ProjectInfo(projectId1, "PRJ1", "Project 1");

        Mockito.when(projectRepository.findProjectInfoByIds(Mockito.anyCollection()))
                .thenReturn(Flux.just(projectInfo1));

        // Act & Assert
        StepVerifier.create(projectService.getProjectInfoByIds(projectIds))
                .assertNext(resultMap -> {
                    Assertions.assertNotNull(resultMap);
                    Assertions.assertEquals(1, resultMap.size());
                    Assertions.assertTrue(resultMap.containsKey(projectId1));
                    Assertions.assertFalse(resultMap.containsKey(projectId2));

                    ProjectInfo actualInfo = resultMap.get(projectId1);
                    Assertions.assertEquals("PRJ1", actualInfo.key());
                    Assertions.assertEquals("Project 1", actualInfo.name());
                })
                .verifyComplete();

        Mockito.verify(projectRepository).findProjectInfoByIds(Mockito.anyCollection());
    }

    @Test
    void getProjectInfoByIds_ReturnsEmptyMap_WithoutCallingRepository_WhenInputIsEmptyOrNull() {
        // Act & Assert для null и пустого списка
        StepVerifier.create(projectService.getProjectInfoByIds(Collections.emptyList()))
                .assertNext(resultMap -> Assertions.assertTrue(resultMap.isEmpty()))
                .verifyComplete();

        StepVerifier.create(projectService.getProjectInfoByIds(null))
                .assertNext(resultMap -> Assertions.assertTrue(resultMap.isEmpty()))
                .verifyComplete();

        Mockito.verifyNoInteractions(projectRepository);
    }

    @Nested
    @DisplayName("getProjectContext")
    class GetProjectContextTests {

        private ProjectMemberDetailsDto memberDetails;
        private ProjectLabelDto label;
        private ProjectWorkflowDto workflow;
        private ProjectCheckMembershipDto memberProjectDto;
        private ProjectCheckMembershipDto globalAdminProjectDto;

        @BeforeEach
        void setUpContextData() {
            memberDetails = ProjectMemberDetailsDto.builder()
                    .userId(actorUserId)
                    .role(ProjectRole.ADMIN)
                    .displayName("Test User")
                    .email("test@example.com")
                    .avatar(null)
                    .addedAt(Instant.now())
                    .build();

            label = ProjectLabelDto.builder()
                    .id(UUID.randomUUID())
                    .name("backend")
                    .color("#3B82F6")
                    .build();

            workflow = new ProjectWorkflowDto(
                    "TASK",
                    WorkflowDto.builder()
                            .id(UUID.randomUUID().toString())
                            .name("Default")
                            .version(1)
                            .statuses(List.of())
                            .transitions(List.of())
                            .build());

            memberProjectDto = ProjectCheckMembershipDto.builder()
                    .project_id(projectId).project_key(projectKey).name(projectName)
                    .created_by(userId).user_id(actorUserId).role(ProjectRole.ADMIN).build();

            // GLOBAL_ADMIN без членства: user_id == null, role == null
            globalAdminProjectDto = ProjectCheckMembershipDto.builder()
                    .project_id(projectId).project_key(projectKey).name(projectName)
                    .created_by(userId).user_id(null).role(null).build();
        }

        @Test
        void asMember_returnsFullContext() {
            Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                    .thenReturn(Mono.just(memberProjectDto));

            Mockito.when(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId))
                    .thenReturn(Flux.just(memberDetails));

            Mockito.when(issueServiceClient.listProjectLabels(projectId, actorUserId, requestId, nodeId))
                    .thenReturn(Mono.just(List.of(label)));

            // loadProjectWorkflows: сначала settings, потом workflow-service
            mockWorkflowLoad();

            StepVerifier.create(projectService.getProjectContext(
                            requestId, nodeId, projectId, actorUserId, GlobalRole.USER))
                    .assertNext(ctx -> {
                        Assertions.assertEquals(memberProjectDto, ctx.project());
                        Assertions.assertEquals(1, ctx.members().size());
                        Assertions.assertEquals(1, ctx.labels().size());
                        Assertions.assertEquals(1, ctx.workflows().size());
                    })
                    .verifyComplete();
        }

        @Test
        void asNonMember_returnsPermissionDenied() {
            Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                    .thenReturn(Mono.just(globalAdminProjectDto));

            StepVerifier.create(projectService.getProjectContext(
                            requestId, nodeId, projectId, actorUserId, GlobalRole.USER))
                    .expectErrorSatisfies(throwable -> {
                        DomainException e = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, e.getStatus());
                    })
                    .verify();

            // Никаких вызовов в members/labels/workflows не было
            Mockito.verifyNoInteractions(projectMemberService);
            Mockito.verifyNoInteractions(issueServiceClient);
            Mockito.verifyNoInteractions(workflowServiceClient);
        }

        @Test
        @DisplayName("GLOBAL_ADMIN без членства → 200, members=[] и labels=[], workflows загружаются")
        void asGlobalAdminWithoutMembership_returnsEmptyMembersAndLabels() {
            Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                    .thenReturn(Mono.just(globalAdminProjectDto));

            // members: PERMISSION_DENIED → onErrorResume → []
            Mockito.when(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId))
                    .thenReturn(Flux.error(new DomainException(
                            DomainStatus.PERMISSION_DENIED, "User has no access to project")));

            // labels: PERMISSION_DENIED → onErrorResume → []
            Mockito.when(issueServiceClient.listProjectLabels(projectId, actorUserId, requestId, nodeId))
                    .thenReturn(Mono.error(new DomainException(
                            DomainStatus.PERMISSION_DENIED, "Access denied")));

            // workflows: загружаются нормально
            mockWorkflowLoad();

            StepVerifier.create(projectService.getProjectContext(
                            requestId, nodeId, projectId, actorUserId, GlobalRole.GLOBAL_ADMIN))
                    .assertNext(ctx -> {
                        Assertions.assertEquals(globalAdminProjectDto, ctx.project());
                        Assertions.assertTrue(ctx.members().isEmpty(), "members должны быть пустыми");
                        Assertions.assertTrue(ctx.labels().isEmpty(), "labels должны быть пустыми");
                        Assertions.assertEquals(1, ctx.workflows().size(),
                                "workflows должны загрузиться, т.к. workflow-service не проверяет актора (TAS-207)");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("GLOBAL_ADMIN без членства: UNAVAILABLE в members НЕ гасится, контекст падает")
        void asGlobalAdmin_unavailableInMembers_propagates() {
            Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                    .thenReturn(Mono.just(globalAdminProjectDto));

            // Не PERMISSION_DENIED, а UNAVAILABLE — должно проброситься
            Mockito.when(projectMemberService.getProjectMembers(requestId, nodeId, projectId, actorUserId))
                    .thenReturn(Flux.error(new DomainException(
                            DomainStatus.UNAVAILABLE, "DB unavailable")));

            Mockito.when(issueServiceClient.listProjectLabels(projectId, actorUserId, requestId, nodeId))
                    .thenReturn(Mono.just(List.of()));
            mockWorkflowLoad();

            StepVerifier.create(projectService.getProjectContext(
                            requestId, nodeId, projectId, actorUserId, GlobalRole.GLOBAL_ADMIN))
                    .expectErrorSatisfies(throwable -> {
                        DomainException e = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.UNAVAILABLE, e.getStatus());
                    })
                    .verify();
        }

        @Test
        @DisplayName("проект не найден → NOT_FOUND, независимо от роли")
        void projectNotFound_returnsNotFound() {
            Mockito.when(projectRepository.findProjectMemberShipDtoByProjectIdAndUserId(projectId, actorUserId))
                    .thenReturn(Mono.empty());

            StepVerifier.create(projectService.getProjectContext(
                            requestId, nodeId, projectId, actorUserId, GlobalRole.GLOBAL_ADMIN))
                    .expectErrorSatisfies(throwable -> {
                        DomainException e = (DomainException) throwable;
                        Assertions.assertEquals(DomainStatus.NOT_FOUND, e.getStatus());
                    })
                    .verify();
        }

        private void mockWorkflowLoad() {
            ObjectNode settings = objectMapper.createObjectNode();
            settings.putArray("allowedIssueTypes").add("TASK");

            var setting = ProjectSetting.builder()
                    .projectId(projectId)
                    .settings(settings)
                    .updatedAt(Instant.now())
                    .updatedBy(userId)
                    .build();

            Mockito.when(projectSettingRepository.findById(projectId))
                    .thenReturn(Mono.just(setting));

            Mockito.lenient().when(workflowServiceClient.getWorkflowForProject(
                            ArgumentMatchers.eq(projectId),
                            ArgumentMatchers.anyString(),
                            ArgumentMatchers.eq(actorUserId),
                            ArgumentMatchers.eq(requestId),
                            ArgumentMatchers.eq(nodeId)))
                    .thenReturn(Mono.just(workflow));
        }
    }
}
