package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;
import ru.taska.domain.Notification;
import ru.taska.repository.EmailDeliveryAttemptRepository;
import ru.taska.repository.NotificationPreferenceRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Сервис отправки email-уведомлений.
 *
 * <p>Проверяет настройки пользователя, отправляет письмо через SMTP
 * и записывает результат в {@code email_delivery_attempts}.</p>
 */

@Service
@RequiredArgsConstructor
public class EmailSenderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSenderService.class);

    private final JavaMailSender mailSender;
    private final NotificationPreferenceRepository preferenceRepository;
    private final EmailDeliveryAttemptRepository attemptRepository;

    /**
     * Отправляет email по уведомлению если пользователь включил email-уведомления.
     *
     * <p>При успехе записывает попытку со статусом {@code SENT}.
     * При ошибке — со статусом {@code FAILED} и планирует повтор.</p>
     */
    public Mono<Void> sendIfEnabled(Notification notification, String toEmail) {
        return preferenceRepository.findByUserId(notification.getUserId())
                .flatMap(preference -> {
                    if (!preference.isEmailEnabled()) {
                        LOGGER.info("Email disabled for userId={}", notification.getUserId());
                        return Mono.<Void>empty();
                    }
                    return doSend(notification, toEmail);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    LOGGER.info("No preference found for userId={}, skipping email", notification.getUserId());
                    return Mono.<Void>empty();
                }));
    }

    private Mono<Void> doSend(Notification notification, String toEmail) {
        return Mono.fromCallable(() -> {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setTo(toEmail);
                    message.setSubject(notification.getTitle());
                    message.setText(notification.getBody() != null ? notification.getBody() : "");
                    mailSender.send(message);
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(saveAttempt(notification, toEmail, EmailAttemptStatus.SENT, null, null))
                .onErrorResume(MailException.class, ex -> {
                    LOGGER.error("Failed to send email for notificationId={}", notification.getId(), ex);
                    return saveAttempt(notification, toEmail, EmailAttemptStatus.FAILED, ex.getMessage(), nextRetryAt());
                })
                .then();
    }

    private Mono<EmailDeliveryAttempt> saveAttempt(
            Notification notification,
            String toEmail,
            EmailAttemptStatus status,
            String lastError,
            Instant nextRetryAt
    ) {
        EmailDeliveryAttempt attempt = EmailDeliveryAttempt.builder()
                .id(UUID.randomUUID())
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
        return attemptRepository.save(attempt);
    }

    private Instant nextRetryAt() {
        return Instant.now().plusSeconds(60);
    }
}
