package ru.taska;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.entity.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;
import ru.taska.service.AdminUserManagementService;
import ru.taska.service.AuthService;
import ru.taska.transport.kafka.OutboxEventScheduler;

class InvitedUnblockIT extends AbstractIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "StrongPassword435!";
    @MockitoBean OutboxEventScheduler scheduler;
    @Autowired UserRepository users;
    @Autowired DatabaseClient database;
    @Autowired AdminUserManagementService management;
    @Autowired AuthService auth;
    @Autowired ReactiveTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"INVITATION", "SIGNUP"})
    void pendingActivationAndOriginalTokenSurviveBlockUnblock(String origin) {
        User user = user(UserStatus.INVITED, UserCreatedVia.valueOf(origin));
        String token = invitation(user);
        var request = request(user);
        management.blockUser("block", "test", request).block(TIMEOUT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getBlockedFromStatus()).isEqualTo(UserStatus.INVITED);
        assertThatThrownBy(() -> auth.setPasswordByToken("blocked", token, PASSWORD).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getStatus()).isEqualTo(DomainStatus.UNAUTHENTICATED));
        assertThat(management.unblockUser("unblock", "test", request).block(TIMEOUT).newStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(auth.previewInvitation(token).block(TIMEOUT).email()).isEqualTo(user.getEmail());
        auth.setPasswordByToken("accept", token, PASSWORD).block(TIMEOUT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id AND credential_type='PASSWORD'", user.getId())).isEqualTo(1);
        String payload = database.sql("SELECT payload::text FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='UserUnblocked'")
                .bind("id", user.getId()).map(row -> row.get(0, String.class)).one().block(TIMEOUT);
        assertThat(payload).contains("INVITED").doesNotContain(token, "blocked_from_status", "secret_hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSWORD", "OIDC", "OAUTH", "OAUTH_ORIGIN", "NONE", "API_TOKEN"})
    void legacyBlocksDistinguishInteractiveActivationFromPendingInvitation(String kind) {
        User user = user(UserStatus.BLOCKED, kind.equals("OAUTH_ORIGIN") ? UserCreatedVia.OAUTH : UserCreatedVia.INVITATION);
        if (!kind.equals("NONE") && !kind.equals("OAUTH_ORIGIN")) credential(user, kind);
        var expected = kind.equals("NONE") || kind.equals("API_TOKEN") ? UserStatus.INVITED : UserStatus.ACTIVE;
        assertThat(management.unblockUser("legacy", "test", request(user)).block(TIMEOUT).newStatus()).isEqualTo(expected);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getBlockedFromStatus()).isNull();
    }

    @Test
    void activeOAuthWithoutPasswordAndRepeatedCyclesRestoreActive() {
        User user = user(UserStatus.ACTIVE, UserCreatedVia.OAUTH);
        credential(user, "OIDC");
        for (int i = 0; i < 2; i++) {
            management.blockUser("block", "test", request(user)).block(TIMEOUT);
            assertThat(users.findById(user.getId()).block(TIMEOUT).getBlockedFromStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(management.unblockUser("unblock", "test", request(user)).block(TIMEOUT).newStatus()).isEqualTo(UserStatus.ACTIVE);
        }
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id AND credential_type='PASSWORD'", user.getId())).isZero();
    }

    @Test
    void outboxFailureRollsBackLifecycleAndRestoreMarkerForBothOperations() {
        User user = user(UserStatus.INVITED, UserCreatedVia.INVITATION);
        rejectEvent("UserBlocked");
        try {
            assertThatThrownBy(() -> management.blockUser("failed", "test", request(user)).block(TIMEOUT)).isInstanceOf(RuntimeException.class);
        } finally { allowEvents(); }
        User unchanged = users.findById(user.getId()).block(TIMEOUT);
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(unchanged.getBlockedFromStatus()).isNull();
        management.blockUser("block", "test", request(user)).block(TIMEOUT);
        rejectEvent("UserUnblocked");
        try {
            assertThatThrownBy(() -> management.unblockUser("failed", "test", request(user)).block(TIMEOUT)).isInstanceOf(RuntimeException.class);
        } finally { allowEvents(); }
        User stillBlocked = users.findById(user.getId()).block(TIMEOUT);
        assertThat(stillBlocked.getStatus()).isEqualTo(UserStatus.BLOCKED);
        assertThat(stillBlocked.getBlockedFromStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(management.unblockUser("retry", "test", request(user)).block(TIMEOUT).newStatus()).isEqualTo(UserStatus.INVITED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activationAndBlockSerializeOnTheUserRow(boolean activationFirst) throws Exception {
        User user = user(UserStatus.INVITED, UserCreatedVia.INVITATION);
        String token = invitation(user);
        var release = Sinks.<Void>one();
        var held = new CountDownLatch(1);
        Mono<Void> activate = Mono.defer(() -> auth.setPasswordByToken("race-accept", token, PASSWORD));
        Mono<Void> block = Mono.defer(() -> management.blockUser("race-block", "test", request(user)).then());
        var first = TransactionalOperator.create(transactions).transactional(
                (activationFirst ? activate : block).then(Mono.fromRunnable(held::countDown)).then(release.asMono())).toFuture();
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            var second = (activationFirst ? block : activate).materialize().toFuture();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                    database.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%taska.users%'")
                            .map(row -> row.get(0, Long.class)).one().block(TIMEOUT) > 0);
            release.tryEmitEmpty();
            first.get(10, TimeUnit.SECONDS);
            var outcome = second.get(10, TimeUnit.SECONDS);
            User blocked = users.findById(user.getId()).block(TIMEOUT);
            assertThat(blocked.getStatus()).isEqualTo(UserStatus.BLOCKED);
            assertThat(blocked.getBlockedFromStatus()).isEqualTo(activationFirst ? UserStatus.ACTIVE : UserStatus.INVITED);
            if (activationFirst) assertThat(outcome.isOnComplete()).isTrue();
            else assertThat(outcome.getThrowable()).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.UNAUTHENTICATED));
            assertThat(management.unblockUser("race-unblock", "test", request(user)).block(TIMEOUT).newStatus())
                    .isEqualTo(activationFirst ? UserStatus.ACTIVE : UserStatus.INVITED);
            if (!activationFirst) auth.setPasswordByToken("retry-accept", token, PASSWORD).block(TIMEOUT);
        } finally { release.tryEmitEmpty(); }
    }

    private User user(UserStatus status, UserCreatedVia origin) {
        return users.save(User.builder().email(UUID.randomUUID()+"@example.test").displayName("User435")
                .status(status).createdVia(origin).build()).block(TIMEOUT);
    }
    private UserStatusRequestDto request(User user) {
        return UserStatusRequestDto.builder().targetUserId(user.getId()).actorUserId(UUID.randomUUID()).reason("TAS-435").build();
    }
    private String invitation(User user) {
        String token = UUID.randomUUID().toString();
        database.sql("INSERT INTO taska.invite_tokens(user_id,token_hash,created_at,expires_at) VALUES(:id,:hash,:created,:expiry)")
                .bind("id", user.getId()).bind("hash", DigestUtils.sha256Hex(token)).bind("created", Instant.now())
                .bind("expiry", Instant.now().plusSeconds(3600)).then().block(TIMEOUT);
        return token;
    }
    private void credential(User user, String type) {
        if (type.equals("PASSWORD") || type.equals("API_TOKEN")) {
            database.sql("INSERT INTO taska.credentials(user_id,credential_type,secret_hash,algo) VALUES(:id,:type,'test-hash','BCRYPT')")
                    .bind("id", user.getId()).bind("type", type).then().block(TIMEOUT);
        } else {
            database.sql("INSERT INTO taska.credentials(user_id,credential_type,provider,subject) VALUES(:id,:type,'test-provider',:subject)")
                    .bind("id", user.getId()).bind("type", type).bind("subject", UUID.randomUUID().toString()).then().block(TIMEOUT);
        }
    }
    private long count(String sql, UUID id) {
        return database.sql(sql).bind("id", id).map(row -> row.get(0, Long.class)).one().block(TIMEOUT);
    }
    private void rejectEvent(String event) {
        database.sql("CREATE FUNCTION taska.reject_435_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='"+event+"' THEN RAISE EXCEPTION 'simulated outbox failure'; END IF; RETURN NEW; END $$").then().block(TIMEOUT);
        database.sql("CREATE TRIGGER reject_435_event BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE FUNCTION taska.reject_435_event()").then().block(TIMEOUT);
    }
    private void allowEvents() {
        database.sql("DROP TRIGGER reject_435_event ON taska.outbox_events").then().block(TIMEOUT);
        database.sql("DROP FUNCTION taska.reject_435_event()").then().block(TIMEOUT);
    }
}
