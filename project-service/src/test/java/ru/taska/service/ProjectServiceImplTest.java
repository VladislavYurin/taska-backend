package ru.taska.service;

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
import ru.taska.domain.ProjectSetting;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.impl.ProjectServiceImpl;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectSettingRepository projectSettingRepository;
    @Mock private OutboxEventService outboxEventService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private ProjectServiceImpl projectService;

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
        Mockito.when(projectRepository.findByProjectIdAndUserId(projectId,actorUserId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectRepository.existsById(projectId)).thenReturn(Mono.just(true));

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId,actorUserId))
                .expectNext(mockProject)
                .verifyComplete();

        Mockito.verify(projectRepository).findByProjectIdAndUserId(projectId,actorUserId);
    }

    @Test
    void getProject_ThrowsNotFoundException_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.findByProjectIdAndUserId(projectId,actorUserId)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.existsById(projectId)).thenReturn(Mono.just(false));

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId,actorUserId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.NOT_FOUND, exception.getStatus());
                    Assertions.assertEquals("Project not found", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findByProjectIdAndUserId(projectId,actorUserId);
        Mockito.verify(projectRepository).existsById(projectId);
    }

    @Test
    void getProject_ThrowsNotFoundException_WhenUserIsNotMember() {
        Mockito.when(projectRepository.findByProjectIdAndUserId(projectId, actorUserId)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.existsById(projectId)).thenReturn(Mono.just(true));

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId, actorUserId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals(DomainStatus.PERMISSION_DENIED, exception.getStatus());
                    Assertions.assertEquals("You don't have access to this project", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findByProjectIdAndUserId(projectId, actorUserId);
        Mockito.verify(projectRepository).existsById(projectId);
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
}