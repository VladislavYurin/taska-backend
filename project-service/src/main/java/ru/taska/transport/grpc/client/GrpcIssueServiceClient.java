package ru.taska.transport.grpc.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsRequestBody;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.dto.projectContext.ProjectLabelDto;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcIssueServiceClient {
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final GrpcClientProperties properties;

    public Mono<Long> countIssueTypeUsage(UUID projectId, String code, String requestId, String nodeId) {
        var request = ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId))
                .addProjectIds(projectId.toString()).setIssueTypeKey(code).setIncludeDeleted(true).build();
        return dynamicStub().countProjectIssuesBatchInternal(request).flatMap(response -> {
            if (!response.getFiltersApplied()) {
                return Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.UNAVAILABLE,
                        "Issue service must be upgraded before deleting issue types"));
            }
            return Mono.just(response.getItemsList().stream()
                    .filter(item -> item.getProjectId().equals(projectId.toString()))
                    .mapToLong(ru.taska.api.issue.v1.ProjectIssueCount::getIssueCount).sum());
        });
    }

    /**
     * Возвращает метки проекта в виде доменных DTO.
     */
    public Mono<List<ProjectLabelDto>> listProjectLabels(
            UUID projectId,
            UUID actorUserId,
            String requestId,
            String nodeId
    ) {
        return dynamicStub().listProjectLabels(
                ListProjectLabelsRequest.newBuilder()
                        .setHeader(Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build())
                        .setBody(ListProjectLabelsRequestBody.newBuilder()
                                .setProjectId(projectId.toString())
                                .setActorUserId(actorUserId.toString())
                                .build())
                        .build()
        ).map(this::toProjectLabelDtoList);
    }

    private List<ProjectLabelDto> toProjectLabelDtoList(ListProjectLabelsResponse response) {
        return response.getLabelsList().stream()
                .map(label ->
                        ProjectLabelDto.builder()
                                .id( UUID.fromString(label.getId()))
                                .name(label.getName())
                                .color(label.getColor())
                                .build()
                )
                .toList();
    }

    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub
                .withDeadlineAfter(properties.issueService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
}
