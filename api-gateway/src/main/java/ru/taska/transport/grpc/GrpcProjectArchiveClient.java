package ru.taska.transport.grpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ArchiveProjectRequest;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.mapper.ProjectMapper;

@Component
@RequiredArgsConstructor
public class GrpcProjectArchiveClient {
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;
    private final GrpcClientProperties properties;
    private final ProjectMapper mapper;

    public Mono<ProjectResponseDto> archive(UUID project, GatewayContext context) {
        return projectServiceStub.withDeadlineAfter(properties.projectService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS)
                .archiveProject(ArchiveProjectRequest.newBuilder().setProjectId(project.toString())
                        .setActorUserId(context.userContext().userId())
                        .setHeader(ru.taska.mapper.MappingUtils.buildGrpcHeader(context)).build())
                .map(mapper::toRestProjectResponse);
    }
}
