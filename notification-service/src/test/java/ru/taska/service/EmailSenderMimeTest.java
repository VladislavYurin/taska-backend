package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.EmailTemplateConfiguration;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationPreference;
import ru.taska.domain.NotificationType;
import ru.taska.mapper.EmailDeliveryAttemptMapper;
import ru.taska.repository.EmailDeliveryAttemptRepository;
import ru.taska.repository.NotificationPreferenceRepository;
import ru.taska.testutil.EmailTestSupport;

class EmailSenderMimeTest {
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
    private final EmailDeliveryAttemptRepository attempts = mock(EmailDeliveryAttemptRepository.class);
    private final Notification notification = Notification.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
            .title("New comment").body("secret legacy preview").issueKey("TAS-1")
            .notificationType(NotificationType.ISSUE_COMMENT_CREATED).build();
    private final java.util.List<EmailDeliveryAttempt> saved = new java.util.ArrayList<>();
    private EmailSenderServiceImpl service;

    @BeforeEach
    void setUp() {
        var factory = new EmailMessageFactory(mail, new EmailTemplateConfiguration().emailTemplateEngine(),
                "https://taska.ozero.dev/#", "#4f46e5", "", "notifications@example.test");
        service = new EmailSenderServiceImpl(mail, factory, preferences, attempts, new EmailDeliveryAttemptMapper());
        ReflectionTestUtils.setField(service, "retryDelay", Duration.ofMinutes(1));
    }

    @Test
    void sendsBothPrivateAlternativesAndRecordsSuccessfulAttempt() {
        enabled();
        when(mail.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        when(attempts.save(any())).thenAnswer(call -> {
            EmailDeliveryAttempt attempt = call.getArgument(0);
            return Mono.fromSupplier(() -> { saved.add(attempt); return attempt; });
        });
        StepVerifier.create(service.sendIfEnabled(notification)).verifyComplete();
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(message.capture());
        for (String body : EmailTestSupport.parts(message.getValue()).values()) {
            assertThat(body).contains("New comment on TAS-1").doesNotContain("secret legacy preview");
        }
        assertThat(saved).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(EmailAttemptStatus.SENT));
    }

    @Test
    void smtpFailureStillSchedulesRetry() {
        enabled();
        when(mail.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        when(attempts.save(any())).thenAnswer(call -> {
            EmailDeliveryAttempt attempt = call.getArgument(0);
            return Mono.fromSupplier(() -> { saved.add(attempt); return attempt; });
        });
        doThrow(new MailSendException("unavailable")).when(mail).send(any(MimeMessage.class));
        StepVerifier.create(service.sendIfEnabled(notification)).verifyComplete();
        assertThat(saved).singleElement().satisfies(attempt -> {
            assertThat(attempt.getStatus()).isEqualTo(EmailAttemptStatus.FAILED);
            assertThat(attempt.getNextRetryAt()).isNotNull();
        });
    }

    @Test
    void disabledEmailDoesNotRenderOrSend() {
        when(preferences.findByUserId(notification.getUserId()))
                .thenReturn(Mono.just(NotificationPreference.builder().emailEnabled(false).build()));
        StepVerifier.create(service.sendIfEnabled(notification)).verifyComplete();
        verifyNoInteractions(mail, attempts);
    }

    private void enabled() {
        when(preferences.findByUserId(notification.getUserId())).thenReturn(Mono.just(
                NotificationPreference.builder().email("user@example.test").emailEnabled(true).build()));
    }
}
