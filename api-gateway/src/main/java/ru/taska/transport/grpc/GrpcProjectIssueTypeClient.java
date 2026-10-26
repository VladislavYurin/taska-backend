package ru.taska.transport.grpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ProjectIssueTypesRequest;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;

@Component
@RequiredArgsConstructor
public class GrpcProjectIssueTypeClient {
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub stub;
    private final GrpcClientProperties properties;

    public Mono<ListProjectIssueTypesDto> list(UUID project, GatewayContext context) {
        return dynamicStub().listProjectIssueTypes(ProjectIssueTypesRequest.newBuilder()
                        .setHeader(header(context)).setProjectId(project.toString())
                        .setActorUserId(context.userContext().userId()).build())
                .map(response -> new ListProjectIssueTypesDto(response.getItemsList().stream().map(GrpcProjectIssueTypeClient::toRest).toList()));
    }

    public Mono<ProjectIssueTypeDto> create(UUID project, CreateProjectIssueTypeRequestDto body, GatewayContext context) {
        var request = base(project, body.getCode(), context);
        if (body.getName() != null) request.setName(body.getName());
        if (body.getColor() != null) request.setColor(body.getColor().getValue());
        if (body.getIcon() != null) request.setIcon(body.getIcon().getValue());
        if (body.getSortOrder() != null) request.setSortOrder(body.getSortOrder());
        return dynamicStub().createProjectIssueType(request.build()).map(GrpcProjectIssueTypeClient::toRest);
    }

    public Mono<ProjectIssueTypeDto> update(UUID project, String code, PatchProjectIssueTypeRequestDto body,
                                           GatewayContext context) {
        var request = base(project, code, context);
        if (body.getName() != null) request.setName(body.getName());
        if (body.getColor() != null) request.setColor(body.getColor());
        if (body.getIcon() != null) request.setIcon(body.getIcon());
        if (body.getSortOrder() != null) request.setSortOrder(body.getSortOrder());
        return dynamicStub().updateProjectIssueType(request.build()).map(GrpcProjectIssueTypeClient::toRest);
    }

    public Mono<ListProjectIssueTypesDto> reorder(UUID project, ReorderProjectIssueTypesRequestDto body, GatewayContext context) {
        return dynamicStub().reorderProjectIssueTypes(ru.taska.api.project.v1.ReorderProjectIssueTypesRequest.newBuilder()
                        .setHeader(header(context)).setProjectId(project.toString()).setActorUserId(context.userContext().userId())
                        .addAllCodes(body.getCodes()).build())
                .map(response -> new ListProjectIssueTypesDto(response.getItemsList().stream().map(GrpcProjectIssueTypeClient::toRest).toList()));
    }

    public Mono<Void> delete(UUID project, String code, GatewayContext context) {
        return dynamicStub().deleteProjectIssueType(base(project, code, context).build()).then();
    }

    public static ProjectIssueTypeDto toRest(ProjectIssueTypeResponse type) {
        return new ProjectIssueTypeDto(type.getCode(), type.getName(),
                ProjectIssueTypeColorDto.fromValue(type.getColor()), ProjectIssueTypeIconDto.fromValue(type.getIcon()),
                type.getSortOrder());
    }

    private ChangeProjectIssueTypeRequest.Builder base(UUID project, String code, GatewayContext context) {
        return ChangeProjectIssueTypeRequest.newBuilder().setHeader(header(context))
                .setProjectId(project.toString()).setActorUserId(context.userContext().userId()).setCode(code);
    }

    private Header header(GatewayContext context) {
        return Header.newBuilder().setRequestId(context.requestId()).setNodeId(context.nodeId()).build();
    }

    private ReactorProjectServiceGrpc.ReactorProjectServiceStub dynamicStub() {
        return stub.withDeadlineAfter(properties.projectService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
}
