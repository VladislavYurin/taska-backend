package ru.taska.grpc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CreateProjectRequest;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.entity.Project;
import ru.taska.entity.ProjectMember;
import ru.taska.entity.ProjectRole;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrpcProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Spy
    private ProjectMapper projectMapper = Mappers.getMapper(ProjectMapper.class);

    @InjectMocks
    private GrpcProjectService grpcProjectService;

    private UUID userId;
    private UUID projectId;
    private Project mockProject;
    private ProjectMember mockMember;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        mockProject = Project.builder()
                .id(projectId)
                .projectKey("TSK")
                .name("Taska Project")
                .createdBy(userId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        mockMember = ProjectMember.builder()
                .projectId(projectId)
                .userId(userId)
                .role(ProjectRole.ADMIN)
                .addedBy(userId)
                .addedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Успешное создание проекта и добавление создателя в качестве ADMIN")
    void createProject_Success() {
        CreateProjectRequest request = CreateProjectRequest.newBuilder()
                .setProjectKey("TSK")
                .setName("Taska Project")
                .setUserId(userId.toString())
                .build();

        when(projectRepository.save(any(Project.class))).thenReturn(Mono.just(mockProject));
        when(projectMemberRepository.save(any(ProjectMember.class))).thenReturn(Mono.just(mockMember));

        Mono<ProjectResponse> resultMono = grpcProjectService.createProject(Mono.just(request));

        StepVerifier.create(resultMono)
                .expectNextMatches(response -> {
                    return response.getId().equals(projectId.toString()) &&
                            response.getProjectKey().equals("TSK") &&
                            response.getName().equals("Taska Project") &&
                            response.getCreatedBy().equals(userId.toString()) &&
                            response.hasCreatedAt() &&
                            !response.hasArchivedAt();
                })
                .verifyComplete();

        verify(projectRepository, times(1)).save(any(Project.class));
        verify(projectMemberRepository, times(1)).save(any(ProjectMember.class));
    }
}