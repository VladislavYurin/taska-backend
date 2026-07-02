package ru.taska.config.props;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow")
public record WorkflowProperties(
        UUID defaultProjectId
) {
}
