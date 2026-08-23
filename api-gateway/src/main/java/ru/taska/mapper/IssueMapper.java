package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.common.v1.Header;
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
import ru.taska.api.issue.v1.SearchIssuesRequest;
import ru.taska.api.issue.v1.SearchIssuesRequestBody;
import ru.taska.api.issue.v1.SearchIssuesResponse;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.IssueHistoryResponseDto;
import ru.taska.domain.dto.IssueLinkResponseDto;
import ru.taska.domain.dto.IssueLinkTypeDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueShortResponseDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssueLinksResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.SearchIssuesRequestDto;
import ru.taska.domain.dto.SearchIssuesResponseDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
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

    public IssueType toGrpcIssueType(IssueTypeDto issueType) {
        if (issueType == null) {
            return IssueType.ISSUE_TYPE_UNSPECIFIED;
        }
        return switch (issueType) {
            case TASK -> IssueType.ISSUE_TYPE_TASK;
            case BUG -> IssueType.ISSUE_TYPE_BUG;
            case STORY -> IssueType.ISSUE_TYPE_STORY;
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

    public IssuePriority toGrpcIssuePriority(IssuePriorityDto priority) {
        if (priority == null) {
            return IssuePriority.ISSUE_PRIORITY_UNSPECIFIED;
        }
        return switch (priority) {
            case LOW -> IssuePriority.ISSUE_PRIORITY_LOW;
            case MEDIUM -> IssuePriority.ISSUE_PRIORITY_MEDIUM;
            case HIGH -> IssuePriority.ISSUE_PRIORITY_HIGH;
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

    /**
     * Создает gRPC запрос для поиска задач.
     */
    public SearchIssuesRequest toSearchIssuesGrpcRequest(
            SearchIssuesRequestDto request,
            GatewayContext context
    ) {
        SearchIssuesRequestBody.Builder bodyBuilder =
                SearchIssuesRequestBody.newBuilder()
                        .setActorUserId(context.userContext().userId());

        setIfPresent(request.getQuery(), bodyBuilder::setQuery);
        setIfPresent(request.getProjectId(), bodyBuilder::setProjectId);
        setIfPresent(request.getStatusKey(), bodyBuilder::setStatusKey);
        setIfPresent(request.getAssigneeId(), bodyBuilder::setAssigneeId);
        setIfPresent(request.getReporterId(), bodyBuilder::setReporterId);

        setIfPresent(request.getPriority(),this::toGrpcIssuePriority,bodyBuilder::setPriority);
        setIfPresent(request.getIssueType(),this::toGrpcIssueType,bodyBuilder::setIssueType);

        setIfPresent(request.getPage(), bodyBuilder::setPage);
        setIfPresent(request.getPageSize(), bodyBuilder::setPageSize);

        return SearchIssuesRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(bodyBuilder.build())
                .build();
    }

    /**
     * Преобразует gRPC ответ поиска в REST DTO.
     */
    public SearchIssuesResponseDto toRestSearchIssuesResponse(SearchIssuesResponse protoDto) {
        SearchIssuesResponseDto restDto = new SearchIssuesResponseDto();
        List<IssueShortResponseDto> items = new ArrayList<>();

        protoDto.getIssuesList()
                .forEach(issue -> items.add(toIssueShortResponseDto(issue)));

        restDto.setItems(items);
        restDto.setTotalCount(protoDto.getTotalCount());

        return restDto;
    }

    /**
     * Безопасно преобразует строку в IssuePriorityDto.
     * Возвращает null если строка null или невалидна.
     */
    public IssuePriorityDto safeParsePriority(String value) {
        if (value == null) {
            return null;
        }
        try {
            return IssuePriorityDto.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid priority value: {}, ignoring", value);
            return null;
        }
    }

    /**
     * Безопасно преобразует строку в IssueTypeDto.
     * Возвращает null если строка null или невалидна.
     */
    public IssueTypeDto safeParseIssueType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return IssueTypeDto.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid issueType value: {}, ignoring", value);
            return null;
        }
    }

    /**
     * Создает SearchIssuesRequestDto из параметров запроса.
     */
    public SearchIssuesRequestDto toSearchRequestDto(
            String query,
            String projectId,
            String statusKey,
            String assigneeId,
            String reporterId,
            String priority,
            String issueType,
            Integer page,
            Integer pageSize
    ) {
        return new SearchIssuesRequestDto()
                .query(query)
                .projectId(projectId)
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .priority(safeParsePriority(priority))
                .issueType(safeParseIssueType(issueType))
                .page(page != null ? page : 0)
                .pageSize(pageSize != null ? pageSize : 20);
    }

    /**
     * Устанавливает значение в билдер, если строка не null и не пустая.
     */
    private static void setIfPresent(String value, Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    /**
     * Устанавливает значение в билдер, если объект не null.
     */
    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Устанавливает значение с преобразованием, если строка не null и не пустая.
     */
    private static <T> void setIfPresent(String value, Function<String, T> converter, Consumer<T> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(converter.apply(value));
        }
    }

    /**
     * Устанавливает значение с преобразованием, если объект не null.
     */
    private static <T, R> void setIfPresent(T value, Function<T, R> converter, Consumer<R> setter) {
        if (value != null) {
            setter.accept(converter.apply(value));
        }
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
