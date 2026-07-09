package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.taska.domain.ProjectRole;

import java.util.Set;
import java.util.UUID;

@ConfigurationProperties(prefix = "workflow")
public record WorkflowProperties(
        UUID defaultProjectId,
        AllowedRoles allowedRoles
) {

    public record AllowedRoles(
            Set<ProjectRole> createWorkflowRoles
    ) {
    }
}
