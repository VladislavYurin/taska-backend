package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.IssueDetails;
import ru.taska.api.issue.v1.IssueHistoryResponse;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.OutboxEvent;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class IssueMapper {

    private final ObjectMapper objectMapper;

    public Issue buildIssue(
            UUID projectId,
            Integer number,
            String issueKey,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId,
            IssueStatus initStatus,
            int initVersion
    ) {
        return Issue.builder()
                .projectId(projectId)
                .issueNumber(number)
                .issueKey(issueKey)
                .issueType(issueType)
                .summary(summary)
                .description(description)
                .statusKey(initStatus)
                .priority(priority)
                .reporterId(reporterId)
                .version(initVersion)
                .build();
    }

    public Issue setIssueAssignee(Issue issue, UUID assigneeId) {
        issue.setAssigneeId(assigneeId);
        return issue;
    }

    public IssueHistory buildIssueHistory(Issue issue, IssueEventType eventType, UUID actorUserId) {
        return IssueHistory.builder()
                .issueId(issue.getId())
                .eventType(eventType)
                .actorUserId(actorUserId)
                .build();
    }

    public OutboxEvent buildOutboxEvent(Issue issue, String aggregateType, String eventType) {
        return OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(issue.getId())
                .eventType(eventType)
                .payload(objectMapper.valueToTree(issue))
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
                .setStatusKey(toProtoIssueStatus(issue.getStatusKey()))
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .setAssigneeId(issue.getAssigneeId() != null ? issue.getAssigneeId().toString() : "")
                .setReporterId(issue.getReporterId().toString())
                .setCreatedAt(toTimestamp(issue.getCreatedAt()))
                .setUpdatedAt(toTimestamp(issue.getUpdatedAt()))
                .setVersion(issue.getVersion())
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

    public IssueDetails toIssueDetailsProto(IssueWithHistory issueWithHistory) {
        var historyProto = issueWithHistory.getHistory().stream()
                .map(this::toIssueHistoryProto)
                .toList();
        return IssueDetails.newBuilder()
                .setIssue(toIssueProto(issueWithHistory.getIssue()))
                .addAllHistory(historyProto)
                .build();
    }

    public IssueShortResponse toIssueShortProto(Issue issue) {
        return IssueShortResponse.newBuilder()
                .setId(issue.getId().toString())
                .setIssueKey(issue.getIssueKey())
                .setSummary(issue.getSummary())
                .setIssueType(toProtoIssueType(issue.getIssueType()))
                .setPriority(toProtoIssuePriority(issue.getPriority()))
                .setAssigneeId(issue.getAssigneeId() != null ? issue.getAssigneeId().toString() : "")
                .setStatusKey(toProtoIssueStatus(issue.getStatusKey()))
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

    public IssueStatus toDomainIssueStatus(ru.taska.api.issue.v1.IssueStatus proto) {
        return switch (proto) {
            case ISSUE_STATUS_TODO -> IssueStatus.TODO;
            case ISSUE_STATUS_IN_PROGRESS -> IssueStatus.IN_PROGRESS;
            case ISSUE_STATUS_DONE -> IssueStatus.DONE;
            default -> throw new IllegalArgumentException("Unknown IssueStatus: " + proto);
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

    private ru.taska.api.issue.v1.IssueEventType toProtoIssueEventType(IssueEventType domain) {
        return switch (domain) {
            case CREATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_CREATED;
            case UPDATED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_UPDATED;
            case ASSIGNED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_ASSIGNED;
            case TRANSITIONED -> ru.taska.api.issue.v1.IssueEventType.ISSUE_EVENT_TYPE_TRANSITIONED;
        };
    }

    private ru.taska.api.issue.v1.IssueType toProtoIssueType(IssueType domain) {
        return switch (domain) {
            case TASK -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK;
            case BUG -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_BUG;
            case STORY -> ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_STORY;
        };
    }

    private ru.taska.api.issue.v1.IssuePriority toProtoIssuePriority(IssuePriority domain) {
        return switch (domain) {
            case LOW -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_LOW;
            case MEDIUM -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM;
            case HIGH -> ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_HIGH;
        };
    }

    private ru.taska.api.issue.v1.IssueStatus toProtoIssueStatus(IssueStatus domain) {
        return switch (domain) {
            case TODO -> ru.taska.api.issue.v1.IssueStatus.ISSUE_STATUS_TODO;
            case IN_PROGRESS -> ru.taska.api.issue.v1.IssueStatus.ISSUE_STATUS_IN_PROGRESS;
            case DONE -> ru.taska.api.issue.v1.IssueStatus.ISSUE_STATUS_DONE;
        };
    }

    private Timestamp toTimestamp(java.time.Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
