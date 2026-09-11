package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("grpc.client")
public record GrpcClientProperties(
        ExternalService authService
) {
    public record ExternalService(
            String host,
            Integer port
    ) {
    }
}
