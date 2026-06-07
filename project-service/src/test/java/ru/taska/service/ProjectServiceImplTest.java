package ru.taska.service;

import ru.taska.exception.DomainException;
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
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.Project;
import ru.taska.entity.ProjectMember;
import ru.taska.entity.ProjectSetting;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.impl.ProjectServiceImpl;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectSettingRepository projectSettingRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProjectMapper projectMapper;

    @InjectMocks private ProjectServiceImpl projectService;

    private final String requestId = "req-123";
    private final String nodeId = "node-1";
    private final String projectKey = "PROJ";
    private final String projectName = "New Project";
    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private Project mockProject;
    private ProjectResponse mockResponse;

    @BeforeEach
    void setUp() {
        mockProject = Project.builder()
                .id(projectId)
                .projectKey(projectKey)
                .name(projectName)
                .createdBy(userId)
                .build();

        mockResponse = ProjectResponse.newBuilder()
                .setId(projectId.toString())
                .setProjectKey(projectKey)
                .setName(projectName)
                .setCreatedBy(userId.toString())
                .build();
    }

    @Test
    void createProject_Success() {
        Mockito.when(projectRepository.findByProjectKey(projectKey)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.save(ArgumentMatchers.any(Project.class))).thenReturn(Mono.just(mockProject));

        ObjectNode mockNode = Mockito.mock(ObjectNode.class);
        Mockito.when(objectMapper.createObjectNode()).thenReturn(mockNode);

        Mockito.when(projectMemberRepository.save(ArgumentMatchers.any(ProjectMember.class))).thenReturn(Mono.just(new ProjectMember()));
        Mockito.when(projectSettingRepository.save(ArgumentMatchers.any(ProjectSetting.class))).thenReturn(Mono.just(new ProjectSetting()));
        Mockito.when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));
        Mockito.when(projectMapper.toResponse(ArgumentMatchers.any(Project.class))).thenReturn(mockResponse);

        StepVerifier.create(projectService.createProject(requestId, nodeId, projectKey, projectName, userId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectRepository).findByProjectKey(projectKey);
        Mockito.verify(projectRepository).save(ArgumentMatchers.any(Project.class));
    }

    @Test
    void getProject_Success() {
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.just(mockProject));
        Mockito.when(projectMapper.toResponse(mockProject)).thenReturn(mockResponse);

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectRepository).findById(projectId);
    }

    @Test
    void getProject_ThrowsNotFoundException_WhenProjectDoesNotExist() {
        Mockito.when(projectRepository.findById(projectId)).thenReturn(Mono.empty());

        StepVerifier.create(projectService.getProject(requestId, nodeId, projectId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals("Project for projectId = " + projectId + " was not found ", exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findById(projectId);
    }

    @Test
    void listMyProjects_Success() {
        Mockito.when(projectRepository.findAllByMemberUserId(userId)).thenReturn(Flux.just(mockProject));
        Mockito.when(projectMapper.toResponse(mockProject)).thenReturn(mockResponse);

        StepVerifier.create(projectService.listMyProjects(requestId, nodeId, userId))
                .expectNext(mockResponse)
                .verifyComplete();

        Mockito.verify(projectRepository).findAllByMemberUserId(userId);
    }

    @Test
    void listMyProjects_ThrowsNotFoundException_WhenNoProjectsFound() {
        Mockito.when(projectRepository.findAllByMemberUserId(userId)).thenReturn(Flux.empty());

        StepVerifier.create(projectService.listMyProjects(requestId, nodeId, userId))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertTrue(throwable instanceof DomainException);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertEquals("Not found projects for user with id: " + userId, exception.getMessage());
                })
                .verify();

        Mockito.verify(projectRepository).findAllByMemberUserId(userId);
    }
}