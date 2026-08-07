package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.issue.v1.IssueEventType;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.dto.IssueHistoryResponseDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueShortResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
        restDto.setStatus(protoDto.getStatusKey());
        restDto.setPriority(this.toRestIssuePriority(protoDto.getPriority()));
        restDto.setReporterId(protoDto.getReporterId());
        restDto.setVersion(protoDto.getVersion());
        restDto.setCreatedAt(toOffsetDateTime(protoDto.getCreatedAt()));
        restDto.setUpdatedAt(toOffsetDateTime(protoDto.getUpdatedAt()));

        restDto.setAssigneeId(protoDto.hasAssigneeId() && !protoDto.getAssigneeId().isBlank() ? protoDto.getAssigneeId() : null);
        restDto.setDescription(protoDto.hasDescription() && !protoDto.getDescription().isBlank() ? protoDto.getDescription() : null);

        restDto.setStoryPoints(protoDto.hasStoryPoints() ? protoDto.getStoryPoints() : null);
        restDto.setOriginalEstimateMinutes(protoDto.hasOriginalEstimateMinutes() ? protoDto.getOriginalEstimateMinutes() : null);
        restDto.setRemainingEstimateMinutes(protoDto.hasRemainingEstimateMinutes() ? protoDto.getRemainingEstimateMinutes() : null);

        restDto.setStartDate(protoDto.hasStartDate() ? toOffsetDateTime(protoDto.getStartDate()) : null);
        restDto.setDueDate(protoDto.hasDueDate() ? toOffsetDateTime(protoDto.getDueDate()) : null);

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

        // Для StringValue проверяем наличие и вытаскиваем значение через .getValue()
        restDto.setSummary(protoDto.hasSummary() ? protoDto.getSummary().getValue() : null);

        // Проверяем StringValue на пустоту/пробелы безопасным образом
        restDto.setDescription(protoDto.hasDescription() && !protoDto.getDescription().getValue().isBlank()
                ? protoDto.getDescription().getValue()
                : null);

        // Проверяем Enum на UNSPECIFIED
        restDto.setPriority(protoDto.hasPriority() && protoDto.getPriority() != IssuePriority.ISSUE_PRIORITY_UNSPECIFIED
                ? toRestIssuePriority(protoDto.getPriority())
                : null);

        // Для DoubleValue
        restDto.setStoryPoints(protoDto.hasStoryPoints() ? protoDto.getStoryPoints().getValue() : null);

        // Для Timestamp по-прежнему проверяем секунды
        restDto.setStartDate(protoDto.hasStartDate() && protoDto.getStartDate().getSeconds() != 0
                ? toOffsetDateTime(protoDto.getStartDate())
                : null);

        restDto.setDueDate(protoDto.hasDueDate() && protoDto.getDueDate().getSeconds() != 0
                ? toOffsetDateTime(protoDto.getDueDate())
                : null);

        // Для Int64Value
        restDto.setOriginalEstimateMinutes(protoDto.hasOriginalEstimateMinutes() ? protoDto.getOriginalEstimateMinutes().getValue() : null);
        restDto.setRemainingEstimateMinutes(protoDto.hasRemainingEstimateMinutes() ? protoDto.getRemainingEstimateMinutes().getValue() : null);

        return restDto;
    }

    public IssueType toGrpcIssueType(String restIssueType) {
        return switch (restIssueType) {
            case "TASK" -> IssueType.ISSUE_TYPE_TASK;
            case "BUG" -> IssueType.ISSUE_TYPE_BUG;
            case "STORY" -> IssueType.ISSUE_TYPE_STORY;
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown issue type: " + restIssueType
            );
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
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown issue priority: " + restIssuePriority
            );
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
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unknown event type: " + grpcIssueEventType
            );
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
