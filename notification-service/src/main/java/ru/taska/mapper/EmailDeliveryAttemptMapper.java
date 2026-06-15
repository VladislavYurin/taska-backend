package ru.taska.mapper;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;
import ru.taska.domain.Notification;

import java.time.Instant;

@Component
public class EmailDeliveryAttemptMapper {

    public SimpleMailMessage toMailMessage(Notification notification, String toEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject(notification.getTitle());
        message.setText(notification.getBody() != null ? notification.getBody() : "");
        return message;
    }

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
