package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.grpc.client")
public record GrpcClientProperties(
        ProjectService projectService
) {

    public record ProjectService(
            String host,
            Integer port
    ) {
    }
}
