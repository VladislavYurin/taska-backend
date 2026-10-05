package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueWorklogBody;
import ru.taska.api.issue.v1.AddIssueWorklogRequest;
import ru.taska.api.issue.v1.DeleteIssueWorklogBody;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsBody;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogBody;
import ru.taska.api.issue.v1.UpdateIssueWorklogRequest;
import ru.taska.api.issue.v1.WorklogResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AddIssueWorklogRequestDto;
import ru.taska.domain.dto.IssueWorklogResponseDto;
import ru.taska.domain.dto.ListIssueWorklogsResponseDto;
import ru.taska.domain.dto.UpdateIssueWorklogRequestDto;

import java.time.LocalDate;
import java.util.UUID;

@Component
public class IssueWorklogMapper {

    public IssueWorklogResponseDto toRestIssueWorklog(WorklogResponse response) {
        var dto = new IssueWorklogResponseDto();

        dto.setId(UUID.fromString(response.getId()));
        dto.setIssueId(UUID.fromString(response.getIssueId()));
        dto.setProjectId(UUID.fromString(response.getProjectId()));
        dto.setAuthorUserId(UUID.fromString(response.getAuthorUserId()));
        dto.setSpentMinutes(response.getSpentMinutes());
        dto.setCreatedAt(GrpcMappingUtils.toOffsetDateTime(response.getCreatedAt()));
        dto.setWorkDate(LocalDate.parse(response.getWorkDate()));

        GrpcMappingUtils.setIfPresent(response::hasUpdatedAt, () -> GrpcMappingUtils.toOffsetDateTime(response.getUpdatedAt()), dto::setUpdatedAt);
        GrpcMappingUtils.setIfPresent(response::hasComment, response::getComment, dto::setComment);

        return dto;
    }


    public ListIssueWorklogsResponseDto toRestListIssueWorklogs(ListIssueWorklogsResponse response) {
        var dto = new ListIssueWorklogsResponseDto();

        dto.setItems(response.getWorklogsList().stream()
                .map(this::toRestIssueWorklog)
                .toList());

        return dto;
    }

    public AddIssueWorklogRequest toAddIssueWorklogGrpcRequest(
            String issueId,
            AddIssueWorklogRequestDto requestDto,
            GatewayContext context
    ) {
        var requestBodyBuilder = AddIssueWorklogBody.newBuilder()
                .setIssueId(issueId)
                .setActorUserId(context.userContext().userId())
                .setSpentMinutes(requestDto.getSpentMinutes())
                .setWorkDate(requestDto.getWorkDate().toString());

        GrpcMappingUtils.setIfPresent(requestDto.getComment(), requestBodyBuilder::setComment);

        return AddIssueWorklogRequest.newBuilder()
                .setHeader(GrpcMappingUtils.buildGrpcHeader(context))
                .setBody(requestBodyBuilder.build())
                .build();
    }

    public DeleteIssueWorklogRequest toDeleteIssueWorklogGrpcRequest(
            String issueId,
            String worklogId,
            GatewayContext context
    ) {
        var requestBodyBuilder = DeleteIssueWorklogBody.newBuilder()
                .setIssueId(issueId)
                .setWorklogId(worklogId)
                .setActorUserId(context.userContext().userId());

        return DeleteIssueWorklogRequest.newBuilder()
                .setHeader(GrpcMappingUtils.buildGrpcHeader(context))
                .setBody(requestBodyBuilder.build())
                .build();
    }

    public ListIssueWorklogsRequest toListIssueWorklogsGrpcRequest(
            String issueId,
            GatewayContext context
    ) {
        var requestBodyBuilder = ListIssueWorklogsBody.newBuilder()
                .setIssueId(issueId)
                .setActorUserId(context.userContext().userId());

        return ListIssueWorklogsRequest.newBuilder()
                .setHeader(GrpcMappingUtils.buildGrpcHeader(context))
                .setBody(requestBodyBuilder.build())
                .build();
    }

    public UpdateIssueWorklogRequest toUpdateIssueWorklogGrpcRequest(
            String issueId,
            String worklogId,
            UpdateIssueWorklogRequestDto requestDto,
            GatewayContext context
    ) {
        var requestBodyBuilder = UpdateIssueWorklogBody.newBuilder()
                .setIssueId(issueId)
                .setWorklogId(worklogId)
                .setActorUserId(context.userContext().userId());

        GrpcMappingUtils.setIfPresent(requestDto.getSpentMinutes(), requestBodyBuilder::setSpentMinutes);
        GrpcMappingUtils.setIfPresent(requestDto.getWorkDate(), LocalDate::toString, requestBodyBuilder::setWorkDate);

        // "" передаём как есть: это означает "очистить комментарий"
        if (requestDto.getComment() != null) {
            requestBodyBuilder.setComment(requestDto.getComment());
        }

        return UpdateIssueWorklogRequest.newBuilder()
                .setHeader(GrpcMappingUtils.buildGrpcHeader(context))
                .setBody(requestBodyBuilder.build())
                .build();
    }

}
