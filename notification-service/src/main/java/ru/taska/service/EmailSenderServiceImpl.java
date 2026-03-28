package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;
import ru.taska.domain.Notification;
import ru.taska.mapper.EmailDeliveryAttemptMapper;
import ru.taska.repository.EmailDeliveryAttemptRepository;
import ru.taska.repository.NotificationPreferenceRepository;

import java.time.Instant;

/**
 * Сервис отправки email-уведомлений.
 *
 * <p>Проверяет настройки пользователя, отправляет письмо через SMTP
 * и записывает результат в {@code email_delivery_attempts}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSenderServiceImpl implements EmailSenderService{

    @Value("${notification.email.retry-delay-seconds}")
    private long retryDelaySeconds;

    private final JavaMailSender mailSender;
    private final NotificationPreferenceRepository preferenceRepository;
    private final EmailDeliveryAttemptRepository attemptRepository;
    private final EmailDeliveryAttemptMapper emailDeliveryAttemptMapper;

    /**
     * Отправляет email по уведомлению если пользователь включил email-уведомления.
     *
     * <p>При успехе записывает попытку со статусом {@code SENT}.
     * При ошибке — со статусом {@code FAILED} и планирует повтор.</p>
     */
    @Override
    public Mono<Void> sendIfEnabled(Notification notification, String toEmail) {
        return preferenceRepository.findByUserId(notification.getUserId())
                .flatMap(preference -> {
                    if (!preference.isEmailEnabled()) {
                        log.info("Email disabled for userId={}", notification.getUserId());
                        return Mono.<Void>empty();
                    }
                    return doSend(notification, toEmail);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("No preference found for userId={}, skipping email", notification.getUserId());
                    return Mono.<Void>empty();
                }));
    }

    private Mono<Void> doSend(Notification notification, String toEmail) {
        return Mono.fromCallable(() -> {
                    mailSender.send(emailDeliveryAttemptMapper.toMailMessage(notification, toEmail));
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(saveAttempt(notification, toEmail, EmailAttemptStatus.SENT, null, null))
                .onErrorResume(MailException.class, ex -> {
                    log.error("Failed to send email for notificationId={}", notification.getId(), ex);
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

        return attemptRepository.save(emailDeliveryAttemptMapper.toAttempt(notification, toEmail, status, lastError, nextRetryAt));
    }

    private Instant nextRetryAt() {
        return Instant.now().plusSeconds(retryDelaySeconds);
    }
}
