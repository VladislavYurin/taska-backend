package ru.taska;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import ru.taska.config.props.SignupProperties;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.*;
import ru.taska.exception.*;
import ru.taska.repository.*;
import ru.taska.service.*;
import ru.taska.transport.kafka.OutboxEventScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties={"taska.signup.enabled=true", "taska.signup.ip-attempts-per-hour=10", "taska.signup.cleanup-interval=1d"})
class EmailSignupIT extends AbstractIT {
    private static final Duration WAIT=Duration.ofSeconds(20);
    @MockitoBean OutboxEventScheduler outboxScheduler;
    @Autowired EmailSignupService signup;
    @Autowired UserRepository users;
    @Autowired InviteTokenRepository tokens;
    @Autowired DatabaseClient database;
    @Autowired AuthService auth;
    @Autowired ProfileService profiles;
    @Autowired UserDirectoryService directory;
    @Autowired SignupCleanup cleanup;
    @Autowired SignupProperties properties;

    @Test void newSignupIsHiddenUntilOneTimeActivationAndCanLogin() {
        String email=email();
        signup.signup("  "+email.toUpperCase()+"  ", "  Alice Example  ", ip(), "signup").block(WAIT);
        User user=users.findByEmail(email).block(WAIT);
        assertThat(user.getDisplayName()).isEqualTo("Alice Example");
        assertThat(user.getCreatedVia()).isEqualTo(UserCreatedVia.SIGNUP);
        assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(user.getGlobalRole()).isEqualTo(GlobalRole.USER);
        assertThat(profiles.getUserDetailsByIds(List.of(user.getId())).collectList().block(WAIT)).isEmpty();
        User admin=account(UserStatus.ACTIVE, GlobalRole.GLOBAL_ADMIN);
        assertThat(directory.search(admin.getId(), email, 0, 20).block(WAIT).getItemsList()).isEmpty();
        String token=raw(user);
        assertThat(auth.previewInvitation(token).block(WAIT).kind()).isEqualTo("SIGNUP");
        auth.setPasswordByToken("accept", token, "StrongPassword204!").block(WAIT);
        assertThat(users.findById(user.getId()).block(WAIT).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(auth.login(email, "StrongPassword204!").block(WAIT).getAccessToken()).isNotBlank();
        assertThat(profiles.getUserDetailsByIds(List.of(user.getId())).collectList().block(WAIT)).hasSize(1);
        rejected(() -> auth.previewInvitation(token).block(WAIT), DomainStatus.UNAUTHENTICATED);
    }
    @Test void resendHonorsCooldownRevokesOldLinkAndPreservesName() {
        String email=email();
        signup.signup(email, "First Name", ip(), "first").block(WAIT);
        User user=users.findByEmail(email).block(WAIT);
        String old=raw(user);
        rejected(() -> signup.signup(email, "Overwrite", ip(), "retry").block(WAIT), DomainStatus.RESOURCE_EXHAUSTED);
        ageEmail(email);
        database.sql("UPDATE taska.invite_tokens SET created_at=now()-interval '61 seconds' WHERE user_id=:id")
                .bind("id", user.getId()).fetch().rowsUpdated().block(WAIT);
        signup.signup(email, "Overwrite", ip(), "resend").block(WAIT);
        assertThat(raw(user)).isNotEqualTo(old);
        assertThat(users.findById(user.getId()).block(WAIT).getDisplayName()).isEqualTo("First Name");
        rejected(() -> auth.previewInvitation(old).block(WAIT), DomainStatus.UNAUTHENTICATED);
        assertThat(auth.previewInvitation(raw(user)).block(WAIT)).isNotNull();
    }
    @Test void allExistingStatesKeepNamesAndStatusesAndBlockedQueuesNothing() {
        for (UserStatus state: UserStatus.values()) {
            User user=account(state, GlobalRole.USER);
            signup.signup(user.getEmail(), "Untrusted replacement", ip(), "existing").block(WAIT);
            User saved=users.findById(user.getId()).block(WAIT);
            assertThat(saved.getStatus()).isEqualTo(state);
            assertThat(saved.getDisplayName()).isEqualTo("Existing Name");
            long events=database.sql("SELECT count(*) AS n FROM taska.outbox_events WHERE aggregate_id=:id")
                    .bind("id", user.getId()).map((r,m)->r.get("n",Long.class)).one().block(WAIT);
            assertThat(events).isEqualTo(state==UserStatus.BLOCKED ? 0 : 1);
            rejected(() -> signup.signup(user.getEmail(), "Again", ip(), "again").block(WAIT), DomainStatus.RESOURCE_EXHAUSTED);
        }
    }
    @Test void ipBudgetIsSharedAcrossAddressesAndRejectedEmailAttemptsCount() {
        String client=ip(), email=email();
        signup.signup(email, "Name", client, "first").block(WAIT);
        for (int i=0;i<9;i++) rejected(() -> signup.signup(email,"Name",client,"repeat").block(WAIT), DomainStatus.RESOURCE_EXHAUSTED);
        String other=email();
        rejected(() -> signup.signup(other,"Name",client,"ip-limit").block(WAIT), DomainStatus.RESOURCE_EXHAUSTED);
        assertThat(users.findByEmail(other).block(WAIT)).isNull();
        signup.signup(other,"Name",ip(),"other-ip").block(WAIT);
    }
    @Test void expiredUnconfirmedSignupIsDeletedAndAddressReusableButActiveAndInvitationsSurvive() {
        String email=email();signup.signup(email,"Old",ip(),"expired").block(WAIT);
        User expired=users.findByEmail(email).block(WAIT);
        database.sql("UPDATE taska.invite_tokens SET created_at=now()-interval '8 days', expires_at=now()-interval '1 second' WHERE user_id=:id")
                .bind("id",expired.getId()).fetch().rowsUpdated().block(WAIT);
        User active=account(UserStatus.ACTIVE,GlobalRole.USER);
        User invited=account(UserStatus.INVITED,GlobalRole.USER);
        cleanup.cleanup().block(WAIT);
        assertThat(users.findById(expired.getId()).block(WAIT)).isNull();
        assertThat(users.findById(active.getId()).block(WAIT)).isNotNull();
        assertThat(users.findById(invited.getId()).block(WAIT)).isNotNull();
        ageEmail(email);signup.signup(email,"New",ip(),"new").block(WAIT);
        assertThat(users.findByEmail(email).block(WAIT).getId()).isNotEqualTo(expired.getId());
    }
    @Test void simultaneousCaseVariantsCreateOneAccountAndOneConfirmation() {
        String email=email();
        var results=Flux.range(0,8).flatMap(i -> signup.signup(i%2==0 ? email : email.toUpperCase(),"Name",ip(),"race")
                .thenReturn("accepted").onErrorResume(DomainException.class,e -> {
                    assertThat(e.getStatus()).isEqualTo(DomainStatus.RESOURCE_EXHAUSTED);
                    return reactor.core.publisher.Mono.just("limited");
                }).subscribeOn(Schedulers.parallel()),8).collectList().block(WAIT);
        assertThat(results).filteredOn("accepted"::equals).hasSize(1);
        User user=users.findByEmail(email).block(WAIT);
        assertThat(tokens.findLatestByUserId(user.getId()).block(WAIT)).isNotNull();
    }
    @Test void outboxFailureRollsBackAccountTokenAndEmailBudgetButCountsIpAttempt() {
        String email=email(), client=ip();
        database.sql("CREATE OR REPLACE FUNCTION taska.reject_signup_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='signup-rollback' THEN RAISE EXCEPTION 'injected signup outbox failure'; END IF; RETURN NEW; END $$")
                .fetch().rowsUpdated().block(WAIT);
        database.sql("CREATE TRIGGER signup_rollback_test BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE FUNCTION taska.reject_signup_test()")
                .fetch().rowsUpdated().block(WAIT);
        try {
            assertThatThrownBy(() -> signup.signup(email,"Name",client,"signup-rollback").block(WAIT)).isInstanceOf(RuntimeException.class);
            assertThat(users.findByEmail(email).block(WAIT)).isNull();
        } finally {
            database.sql("DROP TRIGGER signup_rollback_test ON taska.outbox_events").fetch().rowsUpdated().block(WAIT);
            database.sql("DROP FUNCTION taska.reject_signup_test()").fetch().rowsUpdated().block(WAIT);
        }
        signup.signup(email,"Name",client,"recovery").block(WAIT);
        assertThat(users.findByEmail(email).block(WAIT)).isNotNull();
        Integer attempts=database.sql("SELECT attempts FROM taska.signup_rate_limits WHERE bucket=:bucket")
                .bind("bucket","ip:"+org.apache.commons.codec.digest.DigestUtils.sha256Hex(client))
                .map((r,m)->r.get("attempts",Integer.class)).one().block(WAIT);
        assertThat(attempts).isEqualTo(2);
    }

    private String raw(User user) {
        return database.sql("SELECT payload->>'rawInviteToken' AS token FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='UserInvited' ORDER BY created_at DESC LIMIT 1")
                .bind("id",user.getId()).map((r,m)->r.get("token",String.class)).one().block(WAIT);
    }
    private User account(UserStatus state,GlobalRole role) {
        return users.save(User.builder().email(email()).displayName("Existing Name").status(state).globalRole(role).build()).block(WAIT);
    }
    private void ageEmail(String email) {
        database.sql("UPDATE taska.signup_rate_limits SET started_at=now()-interval '61 seconds' WHERE bucket=:bucket")
                .bind("bucket","email:"+org.apache.commons.codec.digest.DigestUtils.sha256Hex(email)).fetch().rowsUpdated().block(WAIT);
    }
    private static String email(){ return "signup-"+UUID.randomUUID()+"@example.test"; }
    private static String ip(){ return UUID.randomUUID().toString(); }
    private static void rejected(Runnable call,DomainStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getStatus()).isEqualTo(status));
    }
}
