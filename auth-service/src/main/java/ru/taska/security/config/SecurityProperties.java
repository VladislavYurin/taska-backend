package ru.taska.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {
    private int maxFailedAttempts;
    private Duration lockDuration;
    private String passwordEncoder;
}