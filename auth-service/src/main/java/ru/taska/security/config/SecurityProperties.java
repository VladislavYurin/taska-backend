package ru.taska.security.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {
    private int maxFailedAttempts = 5;
    private int lockDurationMinutes = 15;
    private String passwordEncoder = "bcrypt";
}