package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.issue.v1.BoardIssue;
import ru.taska.api.issue.v1.IssueEventType;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.IssueLinkType;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.BoardUserDto;
import ru.taska.domain.dto.IssueHistoryResponseDto;
import ru.taska.domain.dto.IssueLinkResponseDto;
import ru.taska.domain.dto.IssueLinkTypeDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueShortResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssueLinksResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class IssueMapper {

    private final ObjectMapper objectMapper;

    public IssueResponseDto toRestIssueResponse(IssueResponse protoDto) {
        var restDto = new IssueResponseDto();
        restDto.setId(protoDto.getId());
        restDto.setProjectId(protoDto.getProjectId());
        restDto.setIssueNumber(protoDto.getIssueNumber());
        restDto.setIssueKey(protoDto.getIssueKey());
        restDto.setIssueType(this.toRestIssueType(protoDto.getIssueType()));
        restDto.setSummary(protoDto.getSummary());
        restDto.setDescription(protoDto.getDescription());
        restDto.setStatus(protoDto.getStatusKey());
        restDto.setPriority(this.toRestIssuePriority(protoDto.getPriority()));
        restDto.setAssigneeId(protoDto.getAssigneeId());
        restDto.setReporterId(protoDto.getReporterId());
        restDto.setCreatedAt(toOffsetDateTime(protoDto.getCreatedAt()));
        restDto.setUpdatedAt(toOffsetDateTime(protoDto.getUpdatedAt()));
        restDto.setVersion(protoDto.getVersion());

        return restDto;
    }

    public IssueHistoryResponseDto toRestIssueHistoryResponse(IssueHistoryResponse protoDto) {
        var restDto = new IssueHistoryResponseDto();
        restDto.setId(protoDto.getId());
        restDto.setEventType(toRestIssueEventType(protoDto.getEventType()));
        restDto.setActorUserId(protoDto.getActorUserId());
        restDto.setOccurredAt(toOffsetDateTime(protoDto.getOccurredAt()));
        restDto.setPayload(parsePayload(protoDto.getPayload()));

        return restDto;
    }

    public IssueWithHistoryResponseDto toRestIssueWithHistoryResponse(IssueWithHistoryResponse protoDto) {
        var restDto = new IssueWithHistoryResponseDto();
        List<IssueHistoryResponseDto> historyList = new ArrayList<>();

        protoDto.getHistoryList()
                .forEach(history -> historyList.add(this.toRestIssueHistoryResponse(history)));

        restDto.setIssue(this.toRestIssueResponse(protoDto.getIssue()));
        restDto.setHistory(historyList);

        return restDto;
    }

    public IssueShortResponseDto toIssueShortResponseDto(IssueShortResponse protoDto) {
        var restDto = new IssueShortResponseDto();
        restDto.setId(protoDto.getId());
        restDto.setIssueKey(protoDto.getIssueKey());
        restDto.setSummary(protoDto.getSummary());
        restDto.setIssueType(this.toRestIssueType(protoDto.getIssueType()));
        restDto.setPriority(this.toRestIssuePriority(protoDto.getPriority()));
        restDto.setAssigneeId(protoDto.getAssigneeId());

        return restDto;
    }

    public ListIssuesResponseDto toRestListIssuesRequest(ListIssuesResponse protoDto) {
        var restDto = new ListIssuesResponseDto();
        List<IssueShortResponseDto> shortIssues = new ArrayList<>();

        protoDto.getIssuesList()
                .forEach(issue -> shortIssues.add(this.toIssueShortResponseDto(issue)));

        restDto.setItems(shortIssues);
        restDto.setTotalCount(protoDto.getTotalCount());

        return restDto;
    }

    public UpdateIssueResponseDto toRestUpdateResponse(UpdateIssueResponse protoDto) {
        var restDto = new UpdateIssueResponseDto();
        restDto.setId(protoDto.getUpdatedIssueId());
        restDto.setSummary(protoDto.getSummary());
        restDto.setDescription(protoDto.getDescription());
        restDto.setPriority(this.toRestIssuePriority(protoDto.getPriority()));

        return restDto;
    }

    /**
     * Преобразует задачу из gRPC-ответа (BoardIssue) в DTO для REST-ответа (BoardIssueDto).
     */
    public BoardIssueDto toRestBoardIssue(BoardIssue protoDto) {
        var restDto = new BoardIssueDto();

        // Преобразуем String из gRPC в UUID для REST DTO
        restDto.setId(UUID.fromString(protoDto.getId()));

        restDto.setIssueKey(protoDto.getIssueKey());
        restDto.setSummary(protoDto.getSummary());
        restDto.setStoryPoints(protoDto.getStoryPoints());

        if (protoDto.hasAssignee()) {
            var userDto = new BoardUserDto();
            // Здесь также преобразуем строковый ID исполнителя в UUID
            userDto.setId(UUID.fromString(protoDto.getAssignee().getId()));
            userDto.setDisplayName(protoDto.getAssignee().getDisplayName());
            restDto.setAssignee(userDto);
        }

        if (protoDto.getLabelsList() != null) {
            restDto.setLabels(new ArrayList<>(protoDto.getLabelsList()));
        }

        return restDto;
    }

    public IssueLinkResponseDto toRestIssueLinkResponse(IssueLinkResponse protoDto) {
        var restDto = new IssueLinkResponseDto();
        restDto.setId(protoDto.getId());
        restDto.setProjectId(protoDto.getProjectId());
        restDto.setSourceIssueId(protoDto.getSourceIssueId());
        restDto.setTargetIssueId(protoDto.getTargetIssueId());
        restDto.setViewLinkType(protoDto.getViewLinkType().name());
        restDto.setCreatedBy(protoDto.getCreatedBy());
        restDto.setCreatedAt(this.toOffsetDateTime(protoDto.getCreatedAt()));

        return restDto;
    }

    public ListIssueLinksResponseDto toRestListIssueLinkResponse(ListIssueLinksResponse protoDto) {
        var restDto = new ListIssueLinksResponseDto();

        restDto.setItems(
                protoDto.getIssueLinksList().stream()
                        .map(this::toRestIssueLinkResponse)
                        .toList()
        );

        return restDto;
    }

    public IssueType toGrpcIssueType(String restIssueType) {
        return switch (restIssueType) {
            case "TASK" -> IssueType.ISSUE_TYPE_TASK;
            case "BUG" -> IssueType.ISSUE_TYPE_BUG;
            case "STORY" -> IssueType.ISSUE_TYPE_STORY;
            default -> IssueType.ISSUE_TYPE_UNSPECIFIED;
        };
    }

    public String toRestIssueType(IssueType grpcIssueType) {
        return switch (grpcIssueType) {
            case ISSUE_TYPE_TASK -> "TASK";
            case ISSUE_TYPE_BUG -> "BUG";
            case ISSUE_TYPE_STORY -> "STORY";
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown issue type: " + grpcIssueType
            );
        };
    }

    public IssuePriority toGrpcIssuePriority(String restIssuePriority) {
        return switch (restIssuePriority) {
            case "LOW" -> IssuePriority.ISSUE_PRIORITY_LOW;
            case "MEDIUM" -> IssuePriority.ISSUE_PRIORITY_MEDIUM;
            case "HIGH" -> IssuePriority.ISSUE_PRIORITY_HIGH;
            default -> IssuePriority.ISSUE_PRIORITY_UNSPECIFIED;
        };
    }

    public String toRestIssuePriority(IssuePriority grpcIssuePriority) {
        return switch (grpcIssuePriority) {
            case ISSUE_PRIORITY_LOW -> "LOW";
            case ISSUE_PRIORITY_MEDIUM -> "MEDIUM";
            case ISSUE_PRIORITY_HIGH -> "HIGH";
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown issue priority: " + grpcIssuePriority
            );
        };
    }

    public String toRestIssueEventType(IssueEventType grpcIssueEventType) {
        return switch (grpcIssueEventType) {
            case ISSUE_EVENT_TYPE_CREATED -> "CREATED";
            case ISSUE_EVENT_TYPE_UPDATED -> "UPDATED";
            case ISSUE_EVENT_TYPE_ASSIGNED -> "ASSIGNED";
            case ISSUE_EVENT_TYPE_TRANSITIONED -> "TRANSITIONED";
            case ISSUE_EVENT_TYPE_DELETED -> "DELETED";
            case ISSUE_LINK_EVENT_TYPE_CREATED -> "LINK_CREATED";
            case ISSUE_LINK_EVENT_TYPE_DELETED -> "LINK_DELETED";
            case ISSUE_EVENT_TYPE_ATTACHMENT_UPLOADED -> "ATTACHMENT_UPLOADED";
            case ISSUE_EVENT_TYPE_ATTACHMENT_DELETED -> "ATTACHMENT_DELETED";
            case ISSUE_EVENT_TYPE_COMMENT_CREATED -> "COMMENT_CREATED";
            case ISSUE_EVENT_TYPE_COMMENT_UPDATED -> "COMMENT_UPDATED";
            case ISSUE_EVENT_TYPE_COMMENT_DELETED -> "COMMENT_DELETED";
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown event type: " + grpcIssueEventType
            );
        };
    }

    public IssueLinkType toGrpcIssueLinkType(IssueLinkTypeDto restType) {
        return switch (restType) {
            case RELATES_TO -> IssueLinkType.ISSUE_LINK_TYPE_RELATES_TO;
            case BLOCKS -> IssueLinkType.ISSUE_LINK_TYPE_BLOCKS;
            case DUPLICATES -> IssueLinkType.ISSUE_LINK_TYPE_DUPLICATES;
        };
    }

    public OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    public Object parsePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to deserialize payload to JsonNode", e);
        }
    }

    public String convertPayloadToJsonString(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }
}