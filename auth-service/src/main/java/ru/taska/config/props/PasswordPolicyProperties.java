package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "validation.password")
public record PasswordPolicyProperties(
        int min,
        int max,
        boolean requireUpper,
        boolean requireDigit,
        boolean requireSpecial
) {
}
