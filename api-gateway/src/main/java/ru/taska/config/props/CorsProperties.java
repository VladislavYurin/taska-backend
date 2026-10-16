package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

@ConfigurationProperties("app.cors")
public record CorsProperties(
        Set<String> allowedOrigins,
        boolean allowCredentials,
        Set<String> allowedMethods,
        Set<String> allowedHeaders,
        Set<String> exposedHeaders,
        @DefaultValue("7200") long maxAge
) {
}
