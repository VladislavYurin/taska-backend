package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.IssueBoardResponse;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.DeleteIssueLinkResponse;
import ru.taska.api.workflow.v1.IssueValidateSnapshot;
import ru.taska.domain.IdempotencyKey;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.IssueLinkViewType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.IssueWatchStateDto;
import ru.taska.domain.labels.ProjectLabels;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class IssueMapper {

    private final ObjectMapper objectMapper;

    public IdempotencyKey buildIdempotencyKey(String key, UUID userId, String requestHash, Issue response, Duration ttl) {
        return IdempotencyKey.builder()
                .key(key)
                .userId(userId)
                .requestHash(requestHash)
                .response(objectMapper.valueToTree(response))
                .expiresAt(Instant.now().plus(ttl))
                .build();
    }

    public IssueResponse toIssueProto(Issue issue) {
        return IssueResponse.newBuilder()
                .setId(issue.getId().toString())
                .setProjectId(issue.getProjectId().toString())
                .setIssueNumber(issue.getIssueNumber())
                .setIssueKey(issue.getIssueKey())
                .setIssueType(toProtoIssueType(issue.getIssueType()))
                .setSummary(issue.getSummary())
                .setDescription(issue.getDescription() != null ? issue.getDescription() : "")
                .setStatusKey(issue.getStatusKey())
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .setAssigneeId(issue.getAssigneeId() != null ? issue.getAssigneeId().toString() : "")
                .setReporterId(issue.getReporterId().toString())
                .setCreatedAt(toTimestamp(issue.getCreatedAt()))
                .setUpdatedAt(toTimestamp(issue.getUpdatedAt()))
                .setVersion(issue.getVersion())
                .build();
    }

    public IssueResponse toIssueProto(Issue issue, IssueWatchStateDto watchState) {
        return toIssueProto(issue).toBuilder()
                .setWatchersCount((int) watchState.watchersCount())
                .setWatchedByMe(watchState.watchedByMe())
                .build();
    }

    public IssueHistoryResponse toIssueHistoryProto(IssueHistory history) {
        return IssueHistoryResponse.newBuilder()
                .setId(history.getId().toString())
                .setIssueId(history.getIssueId().toString())
                .setEventType(toProtoIssueEventType(history.getEventType()))
                .setActorUserId(history.getActorUserId().toString())
                .setOccurredAt(toTimestamp(history.getOccurredAt()))
                .setPayload(history.getPayload() != null ? history.getPayload().toString() : "")
                .build();
    }

    public IssueWithHistoryResponse toIssueDetailsProto(IssueWithHistory issueWithHistory) {
        var historyProto = issueWithHistory.getHistory().stream()
                .map(this::toIssueHistoryProto)
                .toList();
        var issueProto = toIssueProto(issueWithHistory.getIssue());

        var issueWithLabels = issueProto.toBuilder()
                .addAllLabels(
                        issueWithHistory.getLabels().stream()
                                .map(this::toProjectLabelProto)
                                .toList()
                )
                .build();

        return IssueWithHistoryResponse.newBuilder()
                .setIssue(issueWithLabels)
                .addAllHistory(historyProto)
                .build();
    }

    public IssueWithHistoryResponse toIssueDetailsProto(
            IssueWithHistory issueWithHistory,
            IssueWatchStateDto watchState
    ) {
        var historyProto = issueWithHistory.getHistory().stream()
                .map(this::toIssueHistoryProto)
                .toList();

        var issueProto = toIssueProto(issueWithHistory.getIssue(), watchState).toBuilder()
                .addAllLabels(
                        issueWithHistory.getLabels().stream()
                                .map(this::toProjectLabelProto)
                                .toList()
                )
                .build();

        return IssueWithHistoryResponse.newBuilder()
                .setIssue(issueProto)
                .addAllHistory(historyProto)
                .build();
    }

    /**
     * Domain ProjectLabels → Proto ProjectLabelResponse
     */
    private ProjectLabelResponse toProjectLabelProto(ProjectLabels label) {
        var builder = ProjectLabelResponse.newBuilder()
                .setId(label.getId().toString())
                .setProjectId(label.getProjectId().toString())
                .setName(label.getName())
                .setColor(label.getColor())
                .setCreatedBy(label.getCreatedBy().toString());

        if (label.getCreatedAt() != null) {
            builder.setCreatedAt(toTimestamp(label.getCreatedAt()));
        }

        if (label.getDeletedAt() != null) {
            builder.setDeletedAt(toTimestamp(label.getDeletedAt()));
        }

        return builder.build();
    }

    public IssueShortResponse toIssueShortProto(Issue issue) {
        return IssueShortResponse.newBuilder()
                .setId(issue.getId().toString())
                .setIssueKey(issue.getIssueKey())
                .setSummary(issue.getSummary())
                .setIssueType(toProtoIssueType(issue.getIssueType()))
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .setAssigneeId(issue.getAssigneeId() != null ? issue.getAssigneeId().toString() : "")
                .build();
    }

    /**
     * Мапит задачу со списком меток задачи в proto ответ
     */
    public IssueResponse toIssueProto(Issue issue, List<ProjectLabels> labels) {
        return toIssueProto(issue).toBuilder()
                .addAllLabels(
                        labels.stream()
                                .map(this::toProjectLabelProto)
                                .toList()
                )
                .build();
    }

    public DeleteIssueResponse toDeleteIssueProto(Issue issue) {
        return DeleteIssueResponse.newBuilder()
                .setDeletedIssueId(issue.getId().toString())
                .setIssueEventType(ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_DELETED)
                .build();
    }

    public UpdateIssueResponse toUpdateIssueProto(Issue issue) {
        return UpdateIssueResponse.newBuilder()
                .setUpdatedIssueId(issue.getId().toString())
                .setSummary(issue.getSummary())
                .setDescription(issue.getDescription())
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .build();
    }

    public IssueLinkResponse toIssueLinkProto(IssueLink link, UUID issueId) {
        var issueLinkBuilder = IssueLinkResponse.newBuilder();

        if (link.getDeletedAt() != null) {
            issueLinkBuilder.setDeletedAt(toTimestamp(link.getDeletedAt()));
        }

        return issueLinkBuilder
                .setId(link.getId().toString())
                .setProjectId(link.getProjectId().toString())
                .setSourceIssueId(link.getSourceIssueId().toString())
                .setTargetIssueId(link.getTargetIssueId().toString())
                .setViewLinkType(toProtoIssueLinkViewType(resolveViewType(link, issueId)))
                .setCreatedBy(link.getCreatedBy().toString())
                .setCreatedAt(toTimestamp(link.getCreatedAt()))
                .build();
    }

    public DeleteIssueLinkResponse toDeleteIssueLinkProto(IssueLink link) {
        return DeleteIssueLinkResponse.newBuilder()
                .setLinkId(link.getId().toString())
                .setEventType(ru.taska.api.issue.v1.IssueEventType.ISSUE_LINK_EVENT_TYPE_DELETED)
                .build();
    }

    public ProjectRole toDomainRole(ru.taska.api.project.v1.ProjectRole protoRole) {
        return switch (protoRole) {
            case PROJECT_ROLE_ADMIN -> ProjectRole.ADMIN;
            case PROJECT_ROLE_MEMBER -> ProjectRole.MEMBER;
            case PROJECT_ROLE_VIEWER -> ProjectRole.VIEWER;
            default -> throw new IllegalArgumentException("Unknown ProjectRole: " + protoRole);
        };
    }

    public IssueValidateSnapshot toIssueValidateSnapshotProto(Issue issue) {
        return IssueValidateSnapshot.newBuilder()
                .setIssueId(issue.getId().toString())
                .setProjectId(issue.getProjectId().toString())
                .setIssueType(toWorkflowProtoIssueType(issue.getIssueType()))
                .setStatusKey(issue.getStatusKey())
                .build();
    }

    public IssueType toDomainIssueType(ru.taska.api.issue.v1.IssueType proto) {
        return switch (proto) {
            case ISSUE_TYPE_TASK -> IssueType.TASK;
            case ISSUE_TYPE_BUG -> IssueType.BUG;
            case ISSUE_TYPE_STORY -> IssueType.STORY;
            default -> throw new IllegalArgumentException("Unknown IssueType: " + proto);
        };
    }

    public IssuePriority toDomainIssuePriority(ru.taska.api.issue.v1.IssuePriority proto) {
        return switch (proto) {
            case ISSUE_PRIORITY_LOW -> IssuePriority.LOW;
            case ISSUE_PRIORITY_MEDIUM -> IssuePriority.MEDIUM;
            case ISSUE_PRIORITY_HIGH -> IssuePriority.HIGH;
            default -> throw new IllegalArgumentException("Unknown IssuePriority: " + proto);

        };
    }

    public IssueLinkType toDomainIssueLinkType(ru.taska.api.issue.v1.IssueLinkType proto) {
        return switch (proto) {
            case ISSUE_LINK_TYPE_BLOCKS -> IssueLinkType.BLOCKS;
            case ISSUE_LINK_TYPE_RELATES_TO -> IssueLinkType.RELATES_TO;
            case ISSUE_LINK_TYPE_DUPLICATES -> IssueLinkType.DUPLICATES;
            default -> throw new IllegalArgumentException("Unknown IssueLinkType: " + proto);
        };
    }

    private ru.taska.api.issue.v1.IssueEventType toProtoIssueEventType(IssueEventType domain) {
        return switch (domain) {
            case CREATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_CREATED;
            case UPDATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_UPDATED;
            case ASSIGNED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_ASSIGNED;
            case TRANSITIONED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_TRANSITIONED;
            case DELETED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_DELETED;
            case LINK_CREATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_LINK_EVENT_TYPE_CREATED;
            case LINK_DELETED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_LINK_EVENT_TYPE_DELETED;
            case ATTACHMENT_UPLOADED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_ATTACHMENT_UPLOADED;
            case ATTACHMENT_DELETED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_ATTACHMENT_DELETED;

            case COMMENT_CREATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_COMMENT_CREATED;
            case COMMENT_UPDATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_COMMENT_UPDATED;
            case COMMENT_DELETED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_COMMENT_DELETED;
            case LABEL_ADDED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_LABEL_ADDED;
            case LABEL_REMOVED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_LABEL_REMOVED;
        };
    }

    private ru.taska.api.issue.v1.IssueType toProtoIssueType(IssueType domain) {
        return switch (domain) {
            case TASK -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK;
            case BUG -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_BUG;
            case STORY -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_STORY;
        };
    }

    private ru.taska.api.workflow.v1.IssueType toWorkflowProtoIssueType(IssueType domain) {
        return switch (domain) {
            case TASK -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK;
            case BUG -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_BUG;
            case STORY -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_STORY;
        };
    }

    private ru.taska.api.issue.v1.IssuePriority toProtoIssuePriority(IssuePriority domain) {
        return switch (domain) {
            case LOW -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_LOW;
            case MEDIUM -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM;
            case HIGH -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_HIGH;
        };
    }

    private ru.taska.api.issue.v1.IssueLinkViewType toProtoIssueLinkViewType(IssueLinkViewType domain) {
        return switch (domain) {
            case RELATES_TO -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_RELATES_TO;
            case BLOCKS -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_BLOCKS;
            case IS_BLOCKED_BY -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_IS_BLOCKED_BY;
            case DUPLICATES -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_DUPLICATES;
            case IS_DUPLICATED_BY -> ru.taska.api.issue.v1.IssueLinkViewType.ISSUE_LINK_VIEW_TYPE_IS_DUPLICATED_BY;
        };
    }

    private IssueLinkViewType resolveViewType(IssueLink link, UUID issueId) {
        var isSourceIssue = link.getSourceIssueId().equals(issueId);

        return switch (link.getLinkType()) {
            case RELATES_TO -> IssueLinkViewType.RELATES_TO;
            case BLOCKS -> isSourceIssue ? IssueLinkViewType.BLOCKS : IssueLinkViewType.IS_BLOCKED_BY;
            case DUPLICATES -> isSourceIssue ? IssueLinkViewType.DUPLICATES : IssueLinkViewType.IS_DUPLICATED_BY;
        };
    }

    private Timestamp toTimestamp(java.time.Instant instant) {
        if (instant == null) {
            return null;
        }

        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    /**
     * Маппинг строки результата R2DBC в объект Issue.
     */
    public Issue mapRowToIssue(Row row, RowMetadata metadata) {
        Issue issue = new Issue();

        // Обязательные поля
        issue.setId(row.get("id", UUID.class));
        issue.setProjectId(row.get("project_id", UUID.class));
        issue.setIssueNumber(row.get("issue_number", Integer.class));
        issue.setIssueKey(row.get("issue_key", String.class));
        issue.setIssueType(IssueType.valueOf(row.get("issue_type", String.class)));
        issue.setSummary(row.get("summary", String.class));
        issue.setDescription(row.get("description", String.class));
        issue.setStatusKey(row.get("status_key", String.class));
        issue.setPriority(IssuePriority.valueOf(row.get("priority", String.class)));

        // Опциональные поля
        setOptionalUuidField(issue::setAssigneeId, row.get("assignee_id", String.class));
        setOptionalUuidField(issue::setReporterId, row.get("reporter_id", String.class));

        issue.setVersion(row.get("version", Integer.class));

        // Даты
        setOptionalInstantField(issue::setCreatedAt, row.get("created_at", java.time.OffsetDateTime.class));
        setOptionalInstantField(issue::setUpdatedAt, row.get("updated_at", java.time.OffsetDateTime.class));
        setOptionalInstantField(issue::setDeletedAt, row.get("deleted_at", java.time.OffsetDateTime.class));

        return issue;
    }

    /**
     * Утилитный метод для установки опционального UUID поля.
     */
    private void setOptionalUuidField(java.util.function.Consumer<UUID> setter, String value) {
        if (value != null && !value.isEmpty()) {
            setter.accept(UUID.fromString(value));
        }
    }

    /**
     * Утилитный метод для установки опционального Instant поля из OffsetDateTime.
     */
    private void setOptionalInstantField(java.util.function.Consumer<java.time.Instant> setter, java.time.OffsetDateTime value) {
        if (value != null) {
            setter.accept(value.toInstant());
        }
    }

    public IssueBoardResponse toIssueBoardProto(Issue issue, List<UUID> labelIds, Long commentsCount, Long watchersCount){
        return IssueBoardResponse.newBuilder()
                .setId(issue.getId().toString())
                .setIssueKey(issue.getIssueKey())
                .setSummary(issue.getSummary())
                .setIssueType(toProtoIssueType(issue.getIssueType()))
                .setStatusKey(issue.getStatusKey())
                .setAssigneeId(issue.getAssigneeId() != null ? issue.getAssigneeId().toString(): "")
                .setReporterId(issue.getReporterId().toString())
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .setWatchersCount(watchersCount.intValue())
                .setCommentsCount(commentsCount.intValue())
                .addAllLabelIds(labelIds.stream().map(UUID::toString).toList())
                .build();
    }
}
