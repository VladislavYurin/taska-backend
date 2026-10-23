package ru.taska.integration;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.domain.Notification;
import ru.taska.config.props.TelegramProperties;
import ru.taska.api.common.v1.Header;
import ru.taska.api.notification.v1.NotificationSettingsUserRequest;
import ru.taska.transport.grpc.GrpcNotificationSettingsAdapter;
import ru.taska.domain.NotificationType;
import ru.taska.event.TaskaEvent;
import ru.taska.exception.DomainException;
import ru.taska.repository.NotificationRepository;
import ru.taska.service.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"notification.telegram.enabled=true","notification.telegram.bot-username=TaskaTestBot",
        "notification.telegram.bot-token=123:unit_test_only","notification.telegram.webhook-secret=abcdefghijklmnopqrstuvwxyz012345",
        "management.health.mail.enabled=false"})
class NotificationChannelsIntegrationTest extends AbstractIT {
    private static final String SECRET="abcdefghijklmnopqrstuvwxyz012345";
    private static final Duration WAIT=Duration.ofSeconds(15);
    @Autowired NotificationSettingsService settings;
    @Autowired TelegramUpdateService updates;
    @Autowired NotificationDeliveryQueue queue;
    @Autowired NotificationEventHandler handler;
    @Autowired NotificationRepository notifications;
    @Autowired DatabaseClient db;
    @Autowired ObjectMapper mapper;
    @Autowired TelegramProperties telegramProperties;
    @Autowired GrpcNotificationSettingsAdapter settingsRpc;
    @MockitoBean TelegramSender telegram;
    @MockitoBean JavaMailSender mail;
    private UUID user;

    @BeforeEach
    void prepare() {
        sql("DELETE FROM taska.notification_deliveries");
        sql("DELETE FROM taska.telegram_link_requests");
        sql("DELETE FROM taska.telegram_updates");
        sql("DELETE FROM taska.notification_preferences");
        sql("DELETE FROM taska.notifications");
        sql("DELETE FROM taska.processed_events");
        reset(mail,telegram);
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        when(telegram.notification(anyLong(),any())).thenReturn(Mono.empty());
        when(telegram.reply(anyLong(),anyString())).thenReturn(Mono.empty());
        user=UUID.randomUUID();
        settings.get(user,"user@example.test").block(WAIT);
        readyTelegram();
    }

    @Test
    void privateStartRequiresBrowserConfirmationAndOnlyHashIsStored() {
        var link=settings.createLink(user,"user@example.test").block(WAIT);
        String token=token(link);
        assertThat(token).hasSize(43);
        assertThat(text("SELECT token_hash FROM taska.telegram_link_requests")).hasSize(64).doesNotContain(token);
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramStatus()).isEqualTo("PENDING");
        receive(1,123,"/start "+token);
        var pending=settings.get(user,"user@example.test").block(WAIT);
        assertThat(pending.telegramStatus()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(pending.telegramEnabled()).isFalse();
        assertThat(pending.telegramUsername()).isEqualTo("test_user");
        assertThatThrownBy(() -> settings.update(user,"user@example.test",null,true).block(WAIT)).isInstanceOf(DomainException.class);
        var confirmed=settings.confirm(user,"user@example.test",link.linkId()).block(WAIT);
        assertThat(confirmed.telegramStatus()).isEqualTo("CONNECTED");
        assertThat(confirmed.telegramEnabled()).isTrue();
        assertThat(confirmed.emailEnabled()).isTrue();
        assertThatThrownBy(() -> settings.confirm(user,"user@example.test",link.linkId()).block(WAIT)).isInstanceOf(DomainException.class);
    }

    @Test
    void pollingAndRepeatedProvisioningDoNotRewritePreferencesButVerifiedEmailChangesDo() {
        settings.update(user,"user@example.test",false,null).block(WAIT);
        String version=text("SELECT xmin::text FROM taska.notification_preferences");
        String channelVersion=text("SELECT email_version::text FROM taska.notification_preferences");
        for(int i=0;i<3;i++) {
            settings.get(user,"user@example.test").block(WAIT);
            settings.ensure(user,"user@example.test").block(WAIT);
        }
        assertThat(text("SELECT xmin::text FROM taska.notification_preferences")).isEqualTo(version);
        var updated=settings.get(user,"changed@example.test").block(WAIT);
        assertThat(updated.email()).isEqualTo("changed@example.test");
        assertThat(updated.emailEnabled()).isFalse();
        assertThat(text("SELECT email_version::text FROM taska.notification_preferences")).isNotEqualTo(channelVersion);
        assertThat(settings.get(UUID.randomUUID(),"legacy@example.test").block(WAIT).emailEnabled()).isTrue();
    }

    @Test
    void linkContainsCommittedPendingSnapshotAndPreservesEmailOptOut() {
        settings.update(user,"user@example.test",false,null).block(WAIT);
        var request=NotificationSettingsUserRequest.newBuilder().setUserId(user.toString()).setEmail("user@example.test")
                .setHeader(Header.newBuilder().setRequestId("link-snapshot").setNodeId("test")).build();
        var link=settingsRpc.createTelegramLink(Mono.just(request)).block(WAIT);
        assertThat(link.hasPreferences()).isTrue();
        assertThat(link.getPreferences().getTelegramStatus()).isEqualTo("PENDING");
        assertThat(link.getPreferences().getTelegramLinkId()).isEqualTo(link.getLinkId());
        assertThat(link.getPreferences().getTelegramLinkExpiresAt()).isEqualTo(link.getExpiresAt());
        assertThat(link.getPreferences().getEmailEnabled()).isFalse();
        assertThat(link.getPreferences().getTelegramEnabled()).isFalse();
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramLinkId().toString()).isEqualTo(link.getLinkId());
    }

    @Test
    void disabledIntegrationWithEmptyUsernameDoesNotInventBotChangeOrDiscardBinding() {
        connect(123);
        String bot=telegramProperties.getBotUsername();
        try {
            telegramProperties.setEnabled(false);
            telegramProperties.setBotUsername("");
            var disabled=settings.get(user,"user@example.test").block(WAIT);
            assertThat(disabled.telegramAvailable()).isFalse();
            assertThat(disabled.telegramStatus()).isEqualTo("UNAVAILABLE");
            assertThat(disabled.telegramError()).isNull();
        } finally {
            telegramProperties.setBotUsername(bot);
            telegramProperties.setEnabled(true);
        }
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramStatus()).isEqualTo("CONNECTED");
        try {
            telegramProperties.setBotUsername("DifferentTestBot");
            assertThat(settings.get(user,"user@example.test").block(WAIT).telegramError()).isEqualTo("BOT_CHANGED");
        } finally {
            telegramProperties.setBotUsername(bot);
        }
    }

    @Test
    void foreignExpiredReusedAndGroupTokensDoNotBind() {
        var link=settings.createLink(user,"user@example.test").block(WAIT);
        UUID other=UUID.randomUUID();
        assertThatThrownBy(() -> settings.confirm(other,"other@example.test",link.linkId()).block(WAIT)).isInstanceOf(DomainException.class);
        String group="{\"update_id\":1,\"message\":{\"text\":\"/start "+token(link)+"\",\"from\":{\"id\":123},\"chat\":{\"id\":-123,\"type\":\"group\"}}}";
        updates.process(SECRET,group).block(WAIT);
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramStatus()).isEqualTo("PENDING");
        sql("UPDATE taska.telegram_link_requests SET expires_at=now()-interval '1 second'");
        receive(2,123,"/start "+token(link));
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramStatus()).isEqualTo("NOT_CONNECTED");
        assertThatThrownBy(() -> settings.confirm(user,"user@example.test",link.linkId()).block(WAIT)).isInstanceOf(DomainException.class);
    }

    @Test
    void wrongWebhookSecretLeavesNoReceiptOrReplyAndDuplicateUpdateHasOneReply() {
        assertThatThrownBy(() -> updates.process("wrong",json(1,123,"/help")).block(WAIT)).isInstanceOf(DomainException.class);
        assertThat(count("taska.telegram_updates")).isZero();
        Flux.range(0,2).flatMap(i -> updates.process(SECRET,json(1,123,"/help")).subscribeOn(Schedulers.parallel()),2).then().block(WAIT);
        assertThat(count("taska.telegram_updates")).isEqualTo(1);
        assertThat(count("taska.notification_deliveries")).isEqualTo(1);
        queue.dispatchTelegram().block(WAIT);
        verify(telegram,times(1)).reply(eq(123L),anyString());
    }

    @Test
    void sameTelegramCannotTakeOverAnotherAccountAndConcurrentStartConsumesOnce() {
        var link=settings.createLink(user,"user@example.test").block(WAIT);
        Flux.range(1,2).flatMap(i -> updates.process(SECRET,json(i,123,"/start "+token(link))).subscribeOn(Schedulers.parallel()),2).then().block(WAIT);
        settings.confirm(user,"user@example.test",link.linkId()).block(WAIT);
        UUID other=UUID.randomUUID();
        var otherLink=settings.createLink(other,"other@example.test").block(WAIT);
        receive(3,123,"/start "+token(otherLink));
        assertThatThrownBy(() -> settings.confirm(other,"other@example.test",otherLink.linkId()).block(WAIT)).isInstanceOf(DomainException.class);
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramEnabled()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"true,false,1,0","false,true,0,1","true,true,1,1","false,false,0,0"})
    void fourChannelCombinationsKeepInboxAndCreateOneJobPerChannel(boolean email,boolean tg,int emailSends,int tgSends) {
        connect(123);
        settings.update(user,"user@example.test",email,tg).block(WAIT);
        var event=assignedEvent();
        handler.handle(event).then(handler.handle(event)).block(WAIT);
        queue.dispatchEmail().then(queue.dispatchTelegram()).block(WAIT);
        assertThat(notifications.count().block(WAIT)).isEqualTo(1);
        assertThat(count("taska.notification_deliveries")).isEqualTo(emailSends+tgSends);
        verify(mail,times(emailSends)).send(any(MimeMessage.class));
        verify(telegram,times(tgSends)).notification(eq(123L),any());
    }

    @Test
    void eventAndDeliveryCommitTogetherAndKafkaReplayDoesNotDuplicate() {
        var payload=mapper.createObjectNode().put("assigneeId",user.toString()).put("issueId",UUID.randomUUID().toString())
                .put("projectId",UUID.randomUUID().toString()).put("issueKey","TAS-387").put("summary","Assigned");
        var event=TaskaEvent.builder().id(UUID.randomUUID()).aggregateId(UUID.randomUUID()).aggregateType("issue")
                .eventType("IssueAssigned").occurredAt(Instant.now()).payload(payload).build();
        handler.handle(event).then(handler.handle(event)).block(WAIT);
        assertThat(count("taska.notifications")).isEqualTo(1);
        assertThat(count("taska.notification_deliveries")).isEqualTo(1);
        verify(mail,never()).send(any(MimeMessage.class));
        queue.dispatchEmail().block(WAIT);
        assertThat(text("SELECT status FROM taska.notification_deliveries")).isEqualTo("SENT");
    }

    @Test
    void emailFailureDoesNotAffectTelegramAndRetryDoesNotResendSuccess() {
        connect(123);
        queue.enqueue(notification()).block(WAIT);
        doThrow(new MailSendException("SECRET SMTP BODY MUST NOT BE STORED")).doNothing().when(mail).send(any(MimeMessage.class));
        queue.dispatchEmail().then(queue.dispatchTelegram()).block(WAIT);
        assertThat(text("SELECT status FROM taska.notification_deliveries WHERE channel='EMAIL'")).isEqualTo("RETRY");
        assertThat(text("SELECT status FROM taska.notification_deliveries WHERE channel='TELEGRAM'")).isEqualTo("SENT");
        assertThat(text("SELECT last_error FROM taska.notification_deliveries WHERE channel='EMAIL'")).isEqualTo("EMAIL_TEMPORARY");
        sql("UPDATE taska.notification_deliveries SET next_attempt_at=now() WHERE channel='EMAIL'");
        queue.dispatchEmail().block(WAIT);
        readyTelegram();queue.dispatchTelegram().block(WAIT);
        verify(mail,times(2)).send(any(MimeMessage.class));
        verify(telegram,times(1)).notification(anyLong(),any());
    }

    @Test
    void telegramRateLimitDefersGlobalQueueAndRetryRespectsSentEmail() {
        connect(123);queue.enqueue(notification()).block(WAIT);
        when(telegram.notification(anyLong(),any())).thenReturn(Mono.error(new DeliveryFailure("TELEGRAM_RATE_LIMIT",false,false,120)),Mono.empty());
        queue.dispatchTelegram().then(queue.dispatchEmail()).block(WAIT);
        assertThat(text("SELECT status FROM taska.notification_deliveries WHERE channel='TELEGRAM'")).isEqualTo("RETRY");
        assertThat(db.sql("SELECT next_send_at>now()+interval '110 seconds' FROM taska.notification_channel_throttle")
                .map((r,m) -> r.get(0,Boolean.class)).one().block(WAIT)).isTrue();
        queue.dispatchTelegram().block(WAIT);
        verify(telegram,times(1)).notification(anyLong(),any());
        readyTelegram();sql("UPDATE taska.notification_deliveries SET next_attempt_at=now()");
        queue.dispatchTelegram().then(queue.dispatchEmail()).block(WAIT);
        verify(mail,times(1)).send(any(MimeMessage.class));
        verify(telegram,times(2)).notification(anyLong(),any());
    }

    @Test
    void permanentTelegramErrorDisablesOnlyItsBinding() {
        connect(123);queue.enqueue(notification()).block(WAIT);
        when(telegram.notification(anyLong(),any())).thenReturn(Mono.error(new DeliveryFailure("TELEGRAM_DELIVERY_UNAVAILABLE",true,true,0)));
        queue.dispatchTelegram().then(queue.dispatchEmail()).block(WAIT);
        var p=settings.get(user,"user@example.test").block(WAIT);
        assertThat(p.telegramStatus()).isEqualTo("UNAVAILABLE");assertThat(p.telegramEnabled()).isFalse();assertThat(p.emailEnabled()).isTrue();
        assertThat(text("SELECT status FROM taska.notification_deliveries WHERE channel='TELEGRAM'")).isEqualTo("FAILED");
        settings.disconnect(user,"user@example.test").block(WAIT);
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramStatus()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void optOutThenOptInCannotRestoreOldJobsOrReaddressThemAfterReconnect() {
        connect(123);queue.enqueue(notification()).block(WAIT);
        settings.update(user,"user@example.test",false,false).block(WAIT);
        settings.update(user,"user@example.test",true,true).block(WAIT);
        queue.dispatchEmail().then(queue.dispatchTelegram()).block(WAIT);
        assertThat(db.sql("SELECT count(*) FROM taska.notification_deliveries WHERE status='CANCELLED'")
                .map((r,m) -> r.get(0,Long.class)).one().block(WAIT)).isEqualTo(2);
        verify(mail,never()).send(any(MimeMessage.class));verify(telegram,never()).notification(anyLong(),any());
        queue.enqueue(notification()).block(WAIT);
        receive(2,123,"/stop");
        assertThat(settings.get(user,"user@example.test").block(WAIT).telegramEnabled()).isFalse();
        connect(456);readyTelegram();queue.dispatchTelegram().block(WAIT);
        verify(telegram,never()).notification(anyLong(),any());
    }

    @Test
    void workersClaimOnceRecoverCrashedLeaseAndExhaustAttempts() {
        queue.enqueue(notification()).block(WAIT);
        Flux.range(0,2).flatMap(i -> queue.dispatchEmail().subscribeOn(Schedulers.parallel()),2).then().block(WAIT);
        verify(mail,times(1)).send(any(MimeMessage.class));
        queue.enqueue(notification()).block(WAIT);
        sql("UPDATE taska.notification_deliveries SET status='PROCESSING',attempts=1,last_attempt_at=now()-interval '3 minutes',lease_id=gen_random_uuid() WHERE status='PENDING'");
        queue.dispatchEmail().block(WAIT);
        verify(mail,times(2)).send(any(MimeMessage.class));
        queue.enqueue(notification()).block(WAIT);
        sql("UPDATE taska.notification_deliveries SET status='PROCESSING',attempts=8,last_attempt_at=now()-interval '3 minutes' WHERE status='PENDING'");
        queue.dispatchEmail().block(WAIT);
        verify(mail,times(2)).send(any(MimeMessage.class));
        assertThat(text("SELECT status FROM taska.notification_deliveries WHERE attempts=8")).isEqualTo("FAILED");
    }

    @Test
    void emailSynchronizationPreservesSwitchesAndCancelsOldAddressJobs() {
        queue.enqueue(notification()).block(WAIT);
        settings.get(user,"fresh@example.test").block(WAIT);
        queue.dispatchEmail().block(WAIT);
        verify(mail,never()).send(any(MimeMessage.class));
        settings.update(user,"fresh@example.test",false,null).block(WAIT);
        assertThat(settings.get(user,"fresh@example.test").block(WAIT).emailEnabled()).isFalse();
        queue.observe().then(queue.cleanup()).block(WAIT);
    }

    @Test
    void failedBusinessQueueInsertionRollsBackInboxAndDedupMarker() {
        var event=assignedEvent();
        sql("CREATE FUNCTION taska.reject_delivery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test reject'; END $$");
        sql("CREATE TRIGGER reject_delivery BEFORE INSERT ON taska.notification_deliveries FOR EACH ROW EXECUTE FUNCTION taska.reject_delivery()");
        try {
            assertThatThrownBy(() -> handler.handle(event).block(WAIT)).isInstanceOf(RuntimeException.class);
        } finally {
            sql("DROP TRIGGER reject_delivery ON taska.notification_deliveries");
            sql("DROP FUNCTION taska.reject_delivery()");
        }
        assertThat(count("taska.notifications")).isZero();
        assertThat(count("taska.processed_events")).isZero();
        handler.handle(event).block(WAIT);
        assertThat(count("taska.notifications")).isEqualTo(1);
        assertThat(count("taska.notification_deliveries")).isEqualTo(1);
    }

    @Test
    void newUserActivationProvisionsEmailAndUnsupportedTelegramUpdatesAreIgnored() {
        UUID fresh=UUID.randomUUID();
        var event=TaskaEvent.builder().id(UUID.randomUUID()).aggregateId(fresh).aggregateType("user").eventType("UserActivated")
                .occurredAt(Instant.now()).payload(mapper.createObjectNode().put("email","new@example.test")).build();
        handler.handle(event).block(WAIT);
        assertThat(text("SELECT email FROM taska.notification_preferences WHERE user_id='"+fresh+"'")).isEqualTo("new@example.test");
        updates.process(SECRET,"{\"update_id\":99,\"edited_message\":{}}").block(WAIT);
        assertThat(count("taska.telegram_updates")).isEqualTo(1);
    }

    private TaskaEvent assignedEvent() {
        var payload=mapper.createObjectNode().put("assigneeId",user.toString()).put("issueId",UUID.randomUUID().toString())
                .put("projectId",UUID.randomUUID().toString()).put("issueKey","TAS-387").put("summary","Assigned");
        return TaskaEvent.builder().id(UUID.randomUUID()).aggregateId(UUID.randomUUID()).aggregateType("issue")
                .eventType("IssueAssigned").occurredAt(Instant.now()).payload(payload).build();
    }

    private void connect(long chat) {
        var link=settings.createLink(user,"user@example.test").block(WAIT);
        receive(System.nanoTime()&Long.MAX_VALUE,chat,"/start "+token(link));
        settings.confirm(user,"user@example.test",link.linkId()).block(WAIT);
        sql("DELETE FROM taska.notification_deliveries WHERE notification_id IS NULL");
    }
    private Notification notification() {
        return notifications.save(Notification.builder().userId(user).notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Assigned <test>").body("Safe content").issueId(UUID.randomUUID()).projectId(UUID.randomUUID())
                .issueKey("TAS-387").createdAt(Instant.now()).sourceEventId(UUID.randomUUID()).build()).block(WAIT);
    }
    private String token(NotificationSettingsService.Link link) { return link.url().substring(link.url().indexOf("?start=")+7); }
    private void receive(long id,long chat,String command) { updates.process(SECRET,json(id,chat,command)).block(WAIT); }
    private String json(long id,long chat,String command) {
        return "{\"update_id\":"+id+",\"message\":{\"text\":\""+command+"\",\"from\":{\"id\":"+chat+",\"username\":\"test_user\",\"first_name\":\"Test\"},\"chat\":{\"id\":"+chat+",\"type\":\"private\"}}}";
    }
    private void readyTelegram() { sql("UPDATE taska.notification_channel_throttle SET next_send_at=now()-interval '1 second'"); }
    private void sql(String sql) { db.sql(sql).fetch().rowsUpdated().block(WAIT); }
    private long count(String table) { return db.sql("SELECT count(*) FROM "+table).map((r,m) -> r.get(0,Long.class)).one().block(WAIT); }
    private String text(String sql) { return db.sql(sql).map((r,m) -> r.get(0,String.class)).one().block(WAIT); }
}
