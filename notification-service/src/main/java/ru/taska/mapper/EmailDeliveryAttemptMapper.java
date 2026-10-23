package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;
import ru.taska.domain.Notification;

import java.time.Instant;

@Component
public class EmailDeliveryAttemptMapper {

    public EmailDeliveryAttempt toAttempt(
            Notification notification,
            String toEmail,
            EmailAttemptStatus status,
            String lastError,
            Instant nextRetryAt
    ) {
        return EmailDeliveryAttempt.builder()
                .notificationId(notification.getId())
                .toEmail(toEmail)
                .subject(notification.getTitle())
                .status(status)
                .attempts(1)
                .lastError(lastError)
                .nextRetryAt(nextRetryAt)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
