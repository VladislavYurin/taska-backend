package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.taska.api.project.v1.ProjectRole;

import java.util.Set;

@ConfigurationProperties(prefix = "issue")
public record IssueProperties(
        AllowedRoles allowedRoles,
        List list,
        Card card
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
}
