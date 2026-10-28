package ru.taska.transport.grpc;

import client.ProjectIssueTypes;
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

    public Mono<CreatedProjectIssueTypeDto> create(UUID project, CreateProjectIssueTypeRequestDto body, GatewayContext context) {
        var request = base(project, body.getCode(), context);
        if (body.getName() != null) request.setName(body.getName());
        if (body.getColor() != null) request.setColor(body.getColor().getValue());
        if (body.getShape() != null) request.setShape(body.getShape().getValue());
        if (body.getIcon() != null) request.setIcon(body.getIcon().getValue());
        if (body.getSortOrder() != null) request.setSortOrder(body.getSortOrder());
        if (body.getWorkflowId() != null) request.setWorkflowId(body.getWorkflowId().toString());
        return stub.withDeadlineAfter(120, TimeUnit.SECONDS).createProjectIssueTypeWithWorkflow(request.build())
                .flatMap(result -> {
                    if (!result.hasIssueType()) return Mono.error(GrpcBindingRejection.from(
                            result.getRejectionStatus(), result.getRejectionMessage(), result.getRejectionDetailsMap()));
                    try {
                        return Mono.just(new CreatedProjectIssueTypeDto(toRest(result.getIssueType()), UUID.fromString(result.getWorkflowId())));
                    } catch (IllegalArgumentException malformed) {
                        return Mono.error(new ru.taska.exception.DomainException(
                                ru.taska.exception.DomainStatus.UNAVAILABLE, "Invalid workflow binding response"));
                    }
                });
    }

    public Mono<ProjectIssueTypeDto> update(UUID project, String code, PatchProjectIssueTypeRequestDto body,
                                           GatewayContext context) {
        var request = base(project, code, context);
        if (body.getName() != null) request.setName(body.getName());
        if (body.getColor() != null) request.setColor(body.getColor());
        if (body.getShape() != null) request.setShape(body.getShape());
        // An explicit JSON null travels as clear_icon: an unset optional string already means "unchanged".
        if (body.getIcon().present()) {
            if (body.getIcon().value() == null) request.setClearIcon(true);
            else request.setIcon(body.getIcon().value());
        }
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
        return delete(project, code, null, context);
    }

    public Mono<Void> delete(UUID project, String code, DeleteProjectIssueTypeRequestDto body,
                             GatewayContext context) {
        var request = base(project, code, context);
        boolean moving = body != null && body.getMoveIssuesTo() != null;
        if (moving) request.setMoveIssuesTo(body.getMoveIssuesTo());
        if (body != null && body.getStatusMappings() != null) {
            if (body.getStatusMappings().entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "statusMappings values must be non-null status keys"));
            request.putAllStatusMappings(body.getStatusMappings());
        }
        if (body != null && body.getExpectedGeneration() != null)
            request.setExpectedGeneration(body.getExpectedGeneration().toString());
        if (!moving) return stub.withDeadlineAfter(120, TimeUnit.SECONDS).deleteProjectIssueType(request.build()).then();
        return stub.withDeadlineAfter(120, TimeUnit.SECONDS)
                .deleteProjectIssueTypeWithMigration(request.build())
                .flatMap(result -> {
                    if (result.getCompleted()) return Mono.empty();
                    ru.taska.exception.DomainStatus status;
                    try {
                        status = ru.taska.exception.DomainStatus.valueOf(result.getRejectionStatus());
                    } catch (IllegalArgumentException malformed) {
                        return Mono.error(new ru.taska.exception.DomainException(
                                ru.taska.exception.DomainStatus.UNAVAILABLE, "Invalid issue type deletion response"));
                    }
                    return Mono.error(new ru.taska.exception.DomainException(status,
                            result.getRejectionMessage().isBlank() ? "Issue type deletion was rejected" : result.getRejectionMessage(),
                            result.getRejectionDetailsMap()));
                });
    }

    public static ProjectIssueTypeDto toRest(ProjectIssueTypeResponse type) {
        String icon = ProjectIssueTypes.icon(type);
        var result = new ProjectIssueTypeDto(type.getGeneration().isBlank() ? null : UUID.fromString(type.getGeneration()), type.getCode(), type.getName(),
                ProjectIssueTypeColorDto.fromValue(type.getColor()),
                ProjectIssueTypeShapeDto.fromValue(ProjectIssueTypes.shape(type)),
                icon == null ? null : ProjectIssueTypeIconDto.fromValue(icon), type.getSortOrder());
        if (!type.getGeneration().isBlank()) result.setGeneration(UUID.fromString(type.getGeneration()));
        return result;
    }

    private ChangeProjectIssueTypeRequest.Builder base(UUID project, String code, GatewayContext context) {
        return ChangeProjectIssueTypeRequest.newBuilder().setHeader(header(context))
                .setProjectId(project.toString()).setActorUserId(context.userContext().userId()).setCode(code);
    }

    private Header header(GatewayContext context) {
        return ru.taska.mapper.MappingUtils.buildGrpcHeader(context);
    }

    private ReactorProjectServiceGrpc.ReactorProjectServiceStub dynamicStub() {
        return stub.withDeadlineAfter(properties.projectService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
}
