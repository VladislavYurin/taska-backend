package ru.taska.service.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.api.issue.v1.ProjectIssueCount;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.project.v1.GetProjectMemberStatsBatchRequest;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ListProjectStatsDto;
import ru.taska.domain.dto.ProjectStatsDto;
import ru.taska.mapper.ProjectMapper;
import ru.taska.transport.grpc.GrpcProjectServiceClient;

@Service
@RequiredArgsConstructor
public class ProjectStatsService {
    private final GrpcProjectServiceClient projects;
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectStub;
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueStub;
    private final GrpcClientProperties properties;
    private final ProjectMapper mapper;

    public Mono<ListProjectStatsDto> getProjectStats(GatewayContext context) {
        var header = ru.taska.mapper.MappingUtils.buildGrpcHeader(context);
        return projects.listMyProjects(context).flatMap(response -> {
            var ids = response.getItems().stream().map(p -> p.getId()).distinct().toList();
            if (ids.isEmpty()) {
                return Mono.just(new ListProjectStatsDto(List.of()));
            }
            return projectStub.withDeadlineAfter(properties.projectService().deadlineDuration().toMillis(),
                            TimeUnit.MILLISECONDS)
                    .getProjectMemberStatsBatch(GetProjectMemberStatsBatchRequest.newBuilder().setHeader(header)
                            .setActorUserId(context.userContext().userId()).addAllProjectIds(ids).build())
                    .flatMap(members -> {
                        // A membership removed since ListMyProjects must not expose statistics.
                        var byProject = members.getItemsList().stream().collect(Collectors.toMap(
                                m -> m.getProjectId(), Function.identity()));
                        var authorized = ids.stream().filter(byProject::containsKey).toList();
                        if (authorized.isEmpty()) {
                            return Mono.just(new ListProjectStatsDto(List.of()));
                        }
                        return issueStub.withDeadlineAfter(properties.issueService().deadlineDuration().toMillis(),
                                        TimeUnit.MILLISECONDS)
                                .countProjectIssuesBatchInternal(CountProjectIssuesBatchInternalRequest.newBuilder()
                                        .setHeader(header).addAllProjectIds(authorized).build())
                                .map(counts -> {
                                    Map<String, Long> countByProject = counts.getItemsList().stream()
                                            .collect(Collectors.toMap(ProjectIssueCount::getProjectId,
                                                    ProjectIssueCount::getIssueCount));
                                    return new ListProjectStatsDto(authorized.stream().map(id -> {
                                        var memberStats = byProject.get(id);
                                        return new ProjectStatsDto(id, countByProject.getOrDefault(id, 0L),
                                                memberStats.getMemberCount(), memberStats.getMembersList().stream()
                                                        .map(mapper::toProjectMemberDetailsDto).toList());
                                    }).toList());
                                });
                    });
        });
    }
}
