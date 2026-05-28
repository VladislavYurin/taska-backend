package ru.taska.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.entity.Project;
import ru.taska.entity.ProjectMember;
import ru.taska.entity.ProjectRole;
import ru.taska.mapper.ProjectMapper;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;

import java.time.Instant;
import java.util.UUID;

@GrpcService
@RequiredArgsConstructor
public class GrpcProjectService extends ReactorProjectServiceGrpc.ProjectServiceImplBase {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional
    public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> request) {
        return request.flatMap(req -> {
            Project project = Project.builder()
                    .projectKey(req.getProjectKey())
                    .name(req.getName())
                    .createdBy(UUID.fromString(req.getUserId()))
                    .build();

            return projectRepository.save(project)
                    .flatMap(savedProject -> {
                        ProjectMember adminMember = ProjectMember.builder()
                                .projectId(savedProject.getId())
                                .userId(savedProject.getCreatedBy())
                                .role(ProjectRole.ADMIN)
                                .addedBy(savedProject.getCreatedBy())
                                .addedAt(Instant.now())
                                .build();

                        return projectMemberRepository.save(adminMember)
                                .thenReturn(savedProject);
                    });
        }).map(projectMapper::toResponse);
    }

    @Override
    public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> request) {
        return request
                .flatMap(req -> projectRepository.findById(UUID.fromString(req.getProjectId())))
                .switchIfEmpty(Mono.error(io.grpc.Status.NOT_FOUND
                        .withDescription("Project not found")
                        .asRuntimeException()))
                .map(projectMapper::toResponse);
    }

    @Override
    public Mono<ListMyProjectsResponse> listMyProjects(Mono<ListMyProjectsRequest> request) {
        return request.flatMap(req -> {
            UUID userUuid = UUID.fromString(req.getUserId());

            return projectRepository.findAllByMemberUserId(userUuid)
                    .map(projectMapper::toResponse)
                    .collectList()
                    .map(projectsList -> ListMyProjectsResponse.newBuilder()
                            .addAllProjects(projectsList)
                            .build());
        });
    }
}