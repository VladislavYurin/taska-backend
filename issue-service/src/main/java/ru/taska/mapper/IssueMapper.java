package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;

@Component
public class IssueMapper {

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
