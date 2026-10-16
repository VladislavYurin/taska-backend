package ru.taska.config.props;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@ConfigurationProperties("app.cors")
@Validated
public record CorsProperties(
        Set<String> allowedOrigins,
        boolean allowCredentials,
        Set<String> allowedMethods,
        Set<String> allowedHeaders,
        Set<String> exposedHeaders,
        @Min(0) @DefaultValue("7200") long maxAge
) {
}
