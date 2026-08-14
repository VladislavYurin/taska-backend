package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.taska.domain.ProjectRole;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "issue")
public record IssueProperties(
        AllowedRoles allowedRoles,
        List list,
        Card card,
        IdempotencyKeyTtl idempotencyKeyTtl,
        RetryConfig retry
) {

    public record AllowedRoles(
            Set<ProjectRole> createIssueRoles,
            Set<ProjectRole> assignIssueRoles,
            Set<ProjectRole> updateIssueRoles,
            Set<ProjectRole> deleteIssueRoles,
            Set<ProjectRole> issueTransitionRoles,
            Set<ProjectRole> getIssueRoles,
            Set<ProjectRole> listIssueRoles,
            Set<ProjectRole> createIssueLinksRoles,
            Set<ProjectRole> deleteIssueLinksRoles,
            Set<ProjectRole> listIssueLinksRoles,
            Set<ProjectRole> uploadAttachmentRoles,
            Set<ProjectRole> viewAttachmentRoles,
            Set<ProjectRole> deleteOwnAttachmentRoles,
            Set<ProjectRole> deleteAttachmentRoles,
            Set<ProjectRole> commentRoles,
            Set<ProjectRole> createProjectLabelRoles,   // ADMIN
            Set<ProjectRole> updateProjectLabelRoles,   // ADMIN
            Set<ProjectRole> deleteProjectLabelRoles,   // ADMIN
            Set<ProjectRole> listProjectLabelRoles,     // VIEWER+
            Set<ProjectRole> addIssueLabelRoles,        // MEMBER+
            Set<ProjectRole> removeIssueLabelRoles,     // MEMBER+
            Set<ProjectRole> listIssueLabelRoles        // VIEWER+
    ) {
    }

    public record List(
            int defaultPageSize,
            int maxPageSize
    ) {
    }

    public record Card(int maxHistorySize) {
    }

    public record IdempotencyKeyTtl(Duration ttl) {
    }

    public record RetryConfig(
            int maxAttempts,
            Duration minBackoff
    ) {
    }
}
