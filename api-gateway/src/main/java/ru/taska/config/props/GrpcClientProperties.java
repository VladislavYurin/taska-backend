package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("grpc.client")
public record GrpcClientProperties(
        AuthService authService
) {

    public record AuthService(
            String host,
            Integer port,
            Duration deadlineDuration

    ) {
    }
}
