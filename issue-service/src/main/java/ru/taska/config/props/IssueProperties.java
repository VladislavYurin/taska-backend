package ru.taska.config.props;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.taska.domain.ProjectRole;

import java.util.Set;

@ConfigurationProperties(prefix = "issue")
public record IssueProperties(
        AllowedRoles allowedRoles,
        List list,
        Card card,
        IdempotencyKeyTtl idempotencyKeyTtl
) {

    public record AllowedRoles(
            Set<ProjectRole> createIssueRoles,
            Set<ProjectRole> assignIssueRoles,
            Set<ProjectRole> updateIssueRoles,
            Set<ProjectRole> deleteIssueRoles,
            Set<ProjectRole> getIssueRoles,
            Set<ProjectRole> listIssueRoles
    ) {
    }

    public record List(
            int defaultPageSize,
            int maxPageSize
    ) {
    }

    public record Card(int maxHistorySize) {
    }

    public record IdempotencyKeyTtl(Duration ttl){
    }
}
