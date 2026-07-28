package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("grpc.client")
public record GrpcClientProperties(
        Service authService,
        Service issueService,
        Service projectService,
        Service workflowService,
        Service notificationService
) {
    public record Service(
            String host,
            Integer port,
            Duration deadlineDuration
    ) {
    }
}
