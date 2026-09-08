package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "spring.grpc.client")
public record GrpcClientProperties(
        Service authService
) {
    public record Service(
            String host,
            Integer port,
            Duration deadlineDuration,
            String serviceName
    ) {
    }
}