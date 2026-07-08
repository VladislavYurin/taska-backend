package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.cors")
public record CorsProperties(
        String allowedOrigins,
        boolean allowCredentials,
        String allowedMethods,
        String allowedHeaders
) {
}
