package ru.taska.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import ru.taska.event.TaskaEvent;
import ru.taska.repository.NotificationRepository;
import ru.taska.service.InvitationEmailQueue;
import ru.taska.service.NotificationEventHandler;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@org.springframework.test.context.TestPropertySource(properties = "management.health.mail.enabled=false")
class InvitationEmailQueueIntegrationTest extends AbstractIT {
    @Autowired
    private InvitationEmailQueue queue;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private NotificationEventHandler handler;
    @Autowired
    private NotificationRepository notifications;
    @MockitoBean
    private JavaMailSender mail;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String TOKEN = "a".repeat(43);

    @BeforeEach
    void clearQueue() {
        database.sql("DELETE FROM taska.invitation_email_deliveries").fetch().rowsUpdated().block(TIMEOUT);
    }

    @Test
    void eventIsDurablyQueuedWithoutPreferenceAndInboxNeverContainsTheToken() {
        var event = event(UUID.randomUUID(), TOKEN, Instant.now(), Instant.now().plusSeconds(3600));
        handler.handle(event).block(TIMEOUT);
        handler.handle(event).block(TIMEOUT);
        assertThat(status(event.aggregateId())).isEqualTo("PENDING");
        verifyNoInteractions(mail);
        var inbox = notifications.findAll().filter(item -> event.id().equals(item.getSourceEventId())).collectList().block(TIMEOUT);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().getBody()).doesNotContain(TOKEN);
        queue.dispatchBatch().block(TIMEOUT);
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("invitee@example.test");
        assertThat(captor.getValue().getText()).contains("http://localhost:3001/login?token=" + TOKEN, "After activation, sign in");
        assertThat(status(event.aggregateId())).isEqualTo("SENT");
        assertThat(storedToken(event.aggregateId())).isNull();
        queue.dispatchBatch().block(TIMEOUT);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void smtpFailureRetriesWithoutLosingTheTokenThenClearsItAfterDelivery() {
        var event = event(UUID.randomUUID(), TOKEN, Instant.now(), Instant.now().plusSeconds(3600));
        queue.enqueue(event).block(TIMEOUT);
        doThrow(new MailSendException("temporary SMTP failure")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(event.aggregateId())).isEqualTo("RETRY");
        assertThat(storedToken(event.aggregateId())).isEqualTo(TOKEN);
        database.sql("UPDATE taska.invitation_email_deliveries SET next_attempt_at=now() WHERE user_id=:id")
                .bind("id", event.aggregateId()).fetch().rowsUpdated().block(TIMEOUT);
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(event.aggregateId())).isEqualTo("SENT");
        assertThat(storedToken(event.aggregateId())).isNull();
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void outOfOrderAndDuplicateEventsCannotRestoreAnOlderInvitation() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        var old = event(user, TOKEN, now.minusSeconds(120), now.plusSeconds(3600));
        String freshToken = "b".repeat(43);
        var fresh = event(user, freshToken, now, now.plusSeconds(7200));
        queue.enqueue(fresh).then(queue.enqueue(old)).then(queue.enqueue(fresh)).block(TIMEOUT);
        assertThat(storedToken(user)).isEqualTo(freshToken);
        queue.dispatchBatch().block(TIMEOUT);
        queue.enqueue(fresh).block(TIMEOUT);
        queue.dispatchBatch().block(TIMEOUT);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        assertThat(storedToken(user)).isNull();
    }

    @Test
    void concurrentWorkersClaimOnceAndRecoverAbandonedLeases() {
        var event = event(UUID.randomUUID(), TOKEN, Instant.now(), Instant.now().plusSeconds(3600));
        queue.enqueue(event).block(TIMEOUT);
        Flux.range(0, 2).flatMap(i -> queue.dispatchBatch().subscribeOn(Schedulers.parallel()), 2).then().block(TIMEOUT);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        var recovery = event(UUID.randomUUID(), "c".repeat(43), Instant.now(), Instant.now().plusSeconds(3600));
        queue.enqueue(recovery).block(TIMEOUT);
        database.sql("UPDATE taska.invitation_email_deliveries SET status='PROCESSING', attempts=1, "
                        + "last_attempt_at=now()-interval '3 minutes', lease_id=:lease WHERE user_id=:id")
                .bind("lease", UUID.randomUUID()).bind("id", recovery.aggregateId()).fetch().rowsUpdated().block(TIMEOUT);
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(recovery.aggregateId())).isEqualTo("SENT");
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void expiredAndExhaustedInvitationsAreNotSentAndTheirTokensAreErased() {
        var expired = event(UUID.randomUUID(), TOKEN, Instant.now().minusSeconds(60), Instant.now().minusSeconds(1));
        queue.enqueue(expired).block(TIMEOUT);
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(expired.aggregateId())).isEqualTo("EXPIRED");
        assertThat(storedToken(expired.aggregateId())).isNull();
        verifyNoInteractions(mail);
        var exhausted = event(UUID.randomUUID(), TOKEN, Instant.now(), Instant.now().plusSeconds(3600));
        queue.enqueue(exhausted).block(TIMEOUT);
        database.sql("UPDATE taska.invitation_email_deliveries SET status='PROCESSING', attempts=8, "
                        + "last_attempt_at=now()-interval '3 minutes' WHERE user_id=:id")
                .bind("id", exhausted.aggregateId()).fetch().rowsUpdated().block(TIMEOUT);
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(exhausted.aggregateId())).isEqualTo("FAILED");
        assertThat(storedToken(exhausted.aggregateId())).isNull();
        verifyNoInteractions(mail);
    }

    @Test
    void staleSmtpCompletionCannotEraseTheNextInvitation() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        var first = event(user, TOKEN, now.minusSeconds(120), now.plusSeconds(3600));
        var fresh = event(user, "d".repeat(43), now, now.plusSeconds(7200));
        queue.enqueue(first).block(TIMEOUT);
        doAnswer(invocation -> { queue.enqueue(fresh).block(TIMEOUT); return null; })
                .when(mail).send(any(SimpleMailMessage.class));
        queue.dispatchBatch().block(TIMEOUT);
        assertThat(status(user)).isEqualTo("PENDING");
        assertThat(storedToken(user)).isEqualTo("d".repeat(43));
    }

    @Test
    void inboxFailureRollsBackDeduplicationAndTheEmailQueue() {
        var event = event(UUID.randomUUID(), TOKEN, Instant.now(), Instant.now().plusSeconds(3600));
        database.sql("CREATE FUNCTION taska.reject_invitation_notification() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN RAISE EXCEPTION 'simulated inbox failure'; END $$").fetch().rowsUpdated().block(TIMEOUT);
        database.sql("CREATE TRIGGER reject_invitation_notification BEFORE INSERT ON taska.notifications "
                        + "FOR EACH ROW EXECUTE FUNCTION taska.reject_invitation_notification()")
                .fetch().rowsUpdated().block(TIMEOUT);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(event).block(TIMEOUT))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            database.sql("DROP TRIGGER reject_invitation_notification ON taska.notifications").fetch().rowsUpdated().block(TIMEOUT);
            database.sql("DROP FUNCTION taska.reject_invitation_notification()").fetch().rowsUpdated().block(TIMEOUT);
        }
        assertThat(database.sql("SELECT count(*) FROM taska.invitation_email_deliveries WHERE user_id=:id")
                .bind("id", event.aggregateId()).map(row -> row.get(0, Long.class)).one().block(TIMEOUT)).isZero();
        assertThat(database.sql("SELECT count(*) FROM taska.processed_events WHERE event_id=:id")
                .bind("id", event.id()).map(row -> row.get(0, Long.class)).one().block(TIMEOUT)).isZero();
        handler.handle(event).block(TIMEOUT);
        assertThat(status(event.aggregateId())).isEqualTo("PENDING");
    }

    private TaskaEvent event(UUID user, String token, Instant issued, Instant expires) {
        var payload = JsonMapper.builder().build().createObjectNode().put("userId", user.toString())
                .put("email", "invitee@example.test").put("rawInviteToken", token).put("expiresAt", expires.toString());
        return TaskaEvent.builder().id(UUID.randomUUID()).aggregateId(user).aggregateType("user")
                .eventType("UserInvited").occurredAt(issued).payload(payload).requestId("invitation-test")
                .schemaVersion("v1").producerService("auth-service").build();
    }

    private String status(UUID user) {
        return database.sql("SELECT status FROM taska.invitation_email_deliveries WHERE user_id=:id")
                .bind("id", user).map(row -> row.get(0, String.class)).one().block(TIMEOUT);
    }

    private String storedToken(UUID user) {
        return database.sql("SELECT token FROM taska.invitation_email_deliveries WHERE user_id=:id")
                .bind("id", user).map((row, metadata) -> java.util.Optional.ofNullable(row.get(0, String.class)))
                .one().block(TIMEOUT).orElse(null);
    }
}
