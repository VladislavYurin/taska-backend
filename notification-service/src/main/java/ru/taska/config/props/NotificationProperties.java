package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        Comment comment
) {
    public record Comment(
            int maxShownBodyLength
    ) {}
}
