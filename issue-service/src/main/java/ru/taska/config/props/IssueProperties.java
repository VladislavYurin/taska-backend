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
        RetryConfig retry,
        AutoWatch autoWatch
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
            Set<ProjectRole> watchIssueRoles,
            Set<ProjectRole> listWatchersRoles,
            Set<ProjectRole> manageWatchersRoles

    ) {
    }

    public record List(
            int defaultPageSize,
            int maxPageSize
    ) {
        public int resolvePage(Integer page) {
            if (page == null || page < 0) {
                return 0;
            }
            return page;
        }

        public int resolvePageSize(Integer pageSize) {
            if (pageSize == null || pageSize < 1) {
                return defaultPageSize;
            }
            return Math.min(pageSize, maxPageSize);
        }
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

    public record AutoWatch(
            boolean onCreateReporter,
            boolean onAssignAssignee
    ) {
        public static AutoWatch enabled() {
            return new AutoWatch(true, true);
        }

        public static AutoWatch disabled() {
            return new AutoWatch(false, false);
        }
    }
}
