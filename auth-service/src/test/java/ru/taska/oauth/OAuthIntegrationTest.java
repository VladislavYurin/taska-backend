package ru.taska.oauth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import ru.taska.AbstractIT;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.*;
import ru.taska.exception.DomainException;
import ru.taska.repository.*;
import ru.taska.service.AuthService;
import ru.taska.transport.kafka.OutboxEventScheduler;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "taska.signup.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
class OAuthIntegrationTest extends AbstractIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @MockitoBean OutboxEventScheduler scheduler;
    @Autowired OAuthFlowService flow;
    @Autowired OAuthFlowStore store;
    @Autowired OAuthProperties properties;
    @Autowired OAuthAccountService accounts;
    @Autowired ru.taska.config.props.SignupProperties signup;
    @Autowired OAuthProviderCatalog providers;
    @Autowired UserRepository users;
    @Autowired CredentialRepository credentials;
    @Autowired DatabaseClient database;
    @Autowired AuthService auth;
    @Autowired ru.taska.service.AdminInviteUserService invitations;
    private FakeOAuthProvider fake;
    @BeforeEach void prepare() throws Exception {
        fake = new FakeOAuthProvider(); fake.configure(properties); flag(true);
    }
    @AfterEach void close() { try { fake.assertHealthy(); } finally { fake.close(); } }

    @Test void allProvidersCreateUserOnlyAfterNameAndRepeatLoginUsesStableSubject(CapturedOutput output) {
        for (String provider : List.of("google", "github", "apple")) {
            String subject = provider.equals("github") ? "123456789" : UUID.randomUUID().toString();
            String email = UUID.randomUUID() + (provider.equals("apple") ? "@privaterelay.appleid.com" : "@example.test");
            var start = start(provider, "https://evil.example");
            var url = FakeOAuthProvider.query(URI.create(start.authorizationUrl()).getRawQuery());
            assertThat(url.get("state")).isEqualTo(start.state());
            assertThat(url.get("response_mode")).isEqualTo(provider.equals("apple") ? "form_post" : null);
            String code = fake.issue(provider, subject, email, url);
            assertThat(flow.callback(provider, start.state(), OAuthFlowStore.random(), code, "", "").block(TIMEOUT)).endsWith("error=STATE_INVALID");
            String redirect = callback(provider, start, code);
            assertThat(redirect).startsWith("https://ui.example.test/auth/callback?code=").doesNotContain("accessToken", "refreshToken");
            var exchange = flow.exchange(code(redirect), "exchange").block(TIMEOUT);
            assertThat(exchange.session()).isNull(); assertThat(exchange.signupToken()).isNotBlank();
            assertThat(exchange.returnTo()).isEqualTo("/projects");
            assertThat(exchange.identity().suggestedDisplayName()).isEqualTo(provider.equals("apple") ? "Anna Apple" : "Provider Name");
            assertThat(users.findByEmail(email).block(TIMEOUT)).isNull();
            reason(() -> flow.exchange(code(redirect), "replay").block(TIMEOUT), "STATE_INVALID");
            reason(() -> flow.signup(exchange.signupToken(), "bad\nname", "invalid").block(TIMEOUT), "DISPLAY_NAME_INVALID");
            var session = flow.signup(exchange.signupToken(), "  Chosen Name  ", "signup").block(TIMEOUT);
            var saved = users.findByEmail(email).block(TIMEOUT);
            assertThat(saved.getDisplayName()).isEqualTo("Chosen Name"); assertThat(saved.getCreatedVia()).isEqualTo(UserCreatedVia.OAUTH);
            assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE); assertThat(saved.getGlobalRole()).isEqualTo(GlobalRole.USER);
            assertThat(credentials.findByUserIdAndCredentialType(saved.getId(), CredentialType.PASSWORD).block(TIMEOUT)).isNull();
            assertThat(auth.validateAccessToken(session.getAccessToken()).block(TIMEOUT).getUserId()).isEqualTo(saved.getId().toString());
            reason(() -> flow.signup(exchange.signupToken(), "Other", "replay").block(TIMEOUT), "STATE_INVALID");
            var next = start(provider, "/projects?view=mine");
            String nextCode = fake.issue(provider, subject, email, FakeOAuthProvider.query(URI.create(next.authorizationUrl()).getRawQuery()));
            var signedIn = flow.exchange(code(callback(provider, next, nextCode)), "repeat").block(TIMEOUT);
            assertThat(signedIn.session()).isNotNull(); assertThat(signedIn.signupToken()).isNull();
            assertThat(signedIn.returnTo()).isEqualTo("/projects?view=mine");
            assertThat(output).doesNotContain(code, nextCode, "never-log-google-secret", "never-log-github-secret", "BEGIN PRIVATE KEY", session.getAccessToken(), session.getRefreshToken());
        }
    }
    @Test void activePasswordAccountLinksGoogleWithoutReplacingPasswordOrName() {
        String email = UUID.randomUUID() + "@example.test";
        var user = users.save(User.builder().email(email).displayName("Existing").status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        String password = "StrongPassword204!";
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        credentials.save(Credential.builder().userId(user.getId()).credentialType(CredentialType.PASSWORD).algo(HashingAlgorithm.BCRYPT)
                .secretHash(encoder.encode(password)).failedAttempts(0).build()).block(TIMEOUT);
        var signed = signIn("google", UUID.randomUUID().toString(), email);
        assertThat(auth.validateAccessToken(signed.session().getAccessToken()).block(TIMEOUT).getUserId()).isEqualTo(user.getId().toString());
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo("Existing");
        assertThat(auth.login(email.toUpperCase(Locale.ROOT), password).block(TIMEOUT).getAccessToken()).isNotBlank();
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id", user.getId())).isEqualTo(2);
    }
    @Test void invitedAccountActivatesAndInvalidatesEmailLinkWithoutPassword() {
        var user = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Invited").build()).block(TIMEOUT);
        String token = UUID.randomUUID().toString();
        database.sql("INSERT INTO taska.invite_tokens(user_id,token_hash,expires_at) VALUES(:id,:hash,now()+interval '1 hour')")
                .bind("id", user.getId()).bind("hash", OAuthFlowStore.hash(token)).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(signIn("google", UUID.randomUUID().toString(), user.getEmail()).session()).isNotNull();
        assertThat(users.findById(user.getId()).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(count("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id AND used_at IS NULL", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id AND credential_type='PASSWORD'", user.getId())).isZero();
        assertThatThrownBy(() -> auth.previewInvitation(token).block(TIMEOUT)).isInstanceOf(DomainException.class);
    }
    @Test void blockedAndLockedAreDeniedForLinkedAndUnlinkedAccountsAndExpiredLockResolves() {
        for (UserStatus status : List.of(UserStatus.BLOCKED, UserStatus.LOCKED)) {
            for (boolean linked : List.of(false, true)) {
                var user = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Existing")
                        .status(status).lockedUntil(status == UserStatus.LOCKED ? Instant.now().plusSeconds(60) : null).build()).block(TIMEOUT);
                String subject = UUID.randomUUID().toString();
                if (linked) credentials.save(Credential.builder().userId(user.getId()).credentialType(CredentialType.OIDC).provider("google").subject(subject).failedAttempts(0).build()).block(TIMEOUT);
                var start = start("google", "/projects"); String raw = issue("google", subject, user.getEmail(), start);
                assertThat(callback("google", start, raw)).endsWith("error=" + (status == UserStatus.BLOCKED ? "ACCOUNT_BLOCKED" : "ACCOUNT_LOCKED"));
                assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id", user.getId())).isZero();
            }
        }
        var expired = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Expired").status(UserStatus.LOCKED).lockedUntil(Instant.now().minusSeconds(1)).build()).block(TIMEOUT);
        assertThat(signIn("google", UUID.randomUUID().toString(), expired.getEmail()).session()).isNotNull();
        assertThat(users.findById(expired.getId()).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
    @Test void registrationFlagBlocksNewEmailsButAllowsExistingAccountsAndIsRecheckedAtSignup() {
        String email = UUID.randomUUID() + "@example.test";
        var pending = signIn("google", UUID.randomUUID().toString(), email);
        flag(false);
        reason(() -> flow.signup(pending.signupToken(), "Name", "disabled").block(TIMEOUT), "SIGNUP_DISABLED");
        assertThat(users.findByEmail(email).block(TIMEOUT)).isNull();
        var start = start("google", "/projects"); String raw = issue("google", UUID.randomUUID().toString(), email, start);
        assertThat(callback("google", start, raw)).endsWith("error=SIGNUP_DISABLED");
        var existing = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Existing").status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        assertThat(signIn("google", UUID.randomUUID().toString(), existing.getEmail()).session()).isNotNull();
    }
    @Test void stateCallbackAndExchangeAreOneTimeAndExpiryIsEnforced() {
        var start = start("google", "/projects"); String raw = issue("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", start);
        String redirect = callback("google", start, raw);
        assertThat(callback("google", start, raw)).endsWith("error=STATE_INVALID");
        assertThat(fake.tokenCalls.get()).isEqualTo(1);
        database.sql("UPDATE taska.oauth_grants SET expires_at=now()-interval '1 second' WHERE code_hash=:hash")
                .bind("hash", OAuthFlowStore.hash(code(redirect))).fetch().rowsUpdated().block(TIMEOUT);
        reason(() -> flow.exchange(code(redirect), "expired").block(TIMEOUT), "STATE_INVALID");
        var oldState = start("google", "/projects");
        database.sql("UPDATE taska.oauth_states SET expires_at=now()-interval '1 second' WHERE state_hash=:hash")
                .bind("hash", OAuthFlowStore.hash(oldState.state())).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(callback("google", oldState, "unused")).endsWith("error=STATE_INVALID");
        var cancelled = start("google", "/projects");
        assertThat(flow.callback("google", cancelled.state(), cancelled.browserToken(), "", "access_denied", "").block(TIMEOUT)).endsWith("error=PROVIDER_CANCELLED");
        var pending = signIn("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test");
        database.sql("UPDATE taska.oauth_grants SET expires_at=now()-interval '1 second' WHERE code_hash=:hash")
                .bind("hash", OAuthFlowStore.hash(pending.signupToken())).fetch().rowsUpdated().block(TIMEOUT);
        reason(() -> flow.signup(pending.signupToken(), "Name", "expired").block(TIMEOUT), "STATE_INVALID");
    }
    @Test void oidcRejectsBadSignatureIssuerAudienceNonceExpiryAndUnverifiedEmail() {
        for (String invalid : List.of("signature", "issuer", "audience", "nonce", "expiry", "missing-expiry", "email")) {
            var start = start("google", "/projects"); String raw = issue("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", start);
            var t = fake.tickets.get(raw);
            fake.tickets.put(raw, new FakeOAuthProvider.Ticket(t.provider(), t.subject(), t.email(), invalid.equals("nonce") ? "wrong" : t.nonce(), t.challenge(),
                    !invalid.equals("email"), true, invalid.equals("issuer") ? "https://evil.example" : t.issuer(), invalid.equals("audience") ? "wrong" : t.audience(),
                    invalid.equals("missing-expiry") ? null : invalid.equals("expiry") ? Instant.now().minusSeconds(1) : t.expiry(), t.name(),
                    invalid.equals("signature") ? Jwts.SIG.RS256.keyPair().build() : t.signingKey()));
            assertThat(callback("google", start, raw)).endsWith("error=" + (invalid.equals("email") ? "EMAIL_NOT_VERIFIED" : "PROVIDER_UNAVAILABLE"));
        }
    }
    @Test void githubRequiresVerifiedPrimaryEmailAndUsesIdNotLogin() {
        for (boolean primary : List.of(false, true)) {
            var start = start("github", "/projects"); String raw = issue("github", "987654321", UUID.randomUUID() + "@example.test", start);
            var t = fake.tickets.get(raw);
            fake.tickets.put(raw, new FakeOAuthProvider.Ticket(t.provider(), t.subject(), t.email(), t.nonce(), t.challenge(), !primary, primary, t.issuer(), t.audience(), t.expiry(), t.name(), t.signingKey()));
            assertThat(callback("github", start, raw)).endsWith("error=EMAIL_NOT_VERIFIED");
        }
    }
    @Test void concurrentProvidersShareOneCanonicalEmailAccount() {
        String email = UUID.randomUUID() + "@example.test";
        var first = signIn("google", UUID.randomUUID().toString(), email);
        var second = signIn("apple", UUID.randomUUID().toString(), email.toUpperCase(Locale.ROOT));
        var sessions = Flux.merge(flow.signup(first.signupToken(), "First Name", "one"), flow.signup(second.signupToken(), "Second Name", "two"))
                .collectList().block(TIMEOUT);
        assertThat(sessions).hasSize(2);
        var user = users.findByEmail(email).block(TIMEOUT);
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id", user.getId())).isEqualTo(2);
        assertThat(users.findAll().filter(row -> row.getEmail().equalsIgnoreCase(email)).count().block(TIMEOUT)).isEqualTo(1);
    }
    @Test void existingSubjectTakesPriorityOverAChangedProviderEmail() {
        var first = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("First").status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        var second = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Second").status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        String subject = UUID.randomUUID().toString();
        signIn("google", subject, first.getEmail());
        var again = signIn("google", subject, second.getEmail());
        assertThat(auth.validateAccessToken(again.session().getAccessToken()).block(TIMEOUT).getUserId()).isEqualTo(first.getId().toString());
        assertThat(users.findById(first.getId()).block(TIMEOUT).getEmail()).isEqualTo(first.getEmail());
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id", second.getId())).isZero();
    }
    @Test void exchangeRechecksAccountBlockAfterCallbackAndDoesNotLinkOrIssueSession() {
        var user = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Existing").status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        var start = start("google", "/projects"); String raw = issue("google", UUID.randomUUID().toString(), user.getEmail(), start);
        String grant = code(callback("google", start, raw));
        database.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id").bind("id", user.getId()).fetch().rowsUpdated().block(TIMEOUT);
        reason(() -> flow.exchange(grant, "blocked-after-callback").block(TIMEOUT), "ACCOUNT_BLOCKED");
        reason(() -> flow.exchange(grant, "replay").block(TIMEOUT), "STATE_INVALID");
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM taska.refresh_tokens WHERE user_id=:id", user.getId())).isZero();
    }

    @Test void providerCatalogContainsOnlyConfiguredEnabledProviders() {
        assertThat(providers.enabledProviders()).containsExactly("GOOGLE", "GITHUB", "APPLE");
        properties.getGoogle().setEnabled(false); properties.getGithub().setClientSecret(""); properties.getApple().setPrivateKey("");
        assertThat(providers.enabledProviders()).isEmpty();
        reason(() -> flow.start("google", "/projects").block(TIMEOUT), "PROVIDER_UNAVAILABLE");
    }
    @Test void oauthSignupAndInvitationSerializeOnTheSameCanonicalEmailMutex() {
        String email = UUID.randomUUID() + "@example.test";
        var pending = signIn("google", UUID.randomUUID().toString(), email);
        var admin = users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Admin")
                .globalRole(GlobalRole.GLOBAL_ADMIN).status(UserStatus.ACTIVE).build()).block(TIMEOUT);
        var request = ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder().setRequestId("invite-race").setNodeId("test"))
                .setBody(ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequestBody.newBuilder().setActorUserId(admin.getId().toString())
                        .setEmail(email.toUpperCase(Locale.ROOT)).setDisplayName("Invited Name")).build();
        var results = Flux.merge(flow.signup(pending.signupToken(), "OAuth Name", "signup-race").materialize(),
                invitations.inviteUser(request).materialize()).collectList().block(TIMEOUT);
        assertThat(results).hasSize(2);
        var user = users.findByEmail(email).block(TIMEOUT);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.findAll().filter(row -> row.getEmail().equalsIgnoreCase(email)).count().block(TIMEOUT)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM taska.credentials WHERE user_id=:id", user.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id AND used_at IS NULL", user.getId())).isZero();
    }
    @Test void failedActivationEventRollsBackOAuthAccountCredentialAndSession() {
        String email = UUID.randomUUID() + "@example.test";
        var pending = signIn("google", UUID.randomUUID().toString(), email);
        database.sql("CREATE FUNCTION taska.reject_oauth_activation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.event_type='UserActivated' THEN RAISE EXCEPTION 'simulated oauth failure'; END IF; RETURN NEW; END $$")
                .fetch().rowsUpdated().block(TIMEOUT);
        database.sql("CREATE TRIGGER reject_oauth_activation BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE FUNCTION taska.reject_oauth_activation()")
                .fetch().rowsUpdated().block(TIMEOUT);
        try { assertThatThrownBy(() -> flow.signup(pending.signupToken(), "Name", "failure").block(TIMEOUT)).isInstanceOf(RuntimeException.class); }
        finally {
            database.sql("DROP TRIGGER reject_oauth_activation ON taska.outbox_events").fetch().rowsUpdated().block(TIMEOUT);
            database.sql("DROP FUNCTION taska.reject_oauth_activation()").fetch().rowsUpdated().block(TIMEOUT);
        }
        assertThat(users.findByEmail(email).block(TIMEOUT)).isNull();
        reason(() -> flow.signup(pending.signupToken(), "Name", "replay").block(TIMEOUT), "STATE_INVALID");
        var retry = signIn("google", UUID.randomUUID().toString(), email);
        assertThat(flow.signup(retry.signupToken(), "Name", "retry").block(TIMEOUT).getAccessToken()).isNotBlank();
    }
    @Test void disabledProviderCannotRedeemPreviouslyIssuedGrant() {
        var pending = signIn("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test");
        properties.getGoogle().setEnabled(false);
        reason(() -> flow.signup(pending.signupToken(), "Name", "disabled-provider").block(TIMEOUT), "PROVIDER_UNAVAILABLE");
    }

    private OAuthFlowService.Start start(String provider, String returnTo) { return flow.start(provider, returnTo).block(TIMEOUT); }
    private String issue(String provider, String subject, String email, OAuthFlowService.Start start) { return fake.issue(provider, subject, email, FakeOAuthProvider.query(URI.create(start.authorizationUrl()).getRawQuery())); }
    private String callback(String provider, OAuthFlowService.Start start, String raw) {
        return flow.callback(provider, start.state(), start.browserToken(), raw, "", "{\"name\":{\"firstName\":\"Anna\",\"lastName\":\"Apple\"}}").block(TIMEOUT);
    }
    private OAuthFlowService.Exchange signIn(String provider, String subject, String email) {
        var start = start(provider, "/projects"); return flow.exchange(code(callback(provider, start, issue(provider, subject, email, start))), "signin").block(TIMEOUT);
    }
    private String code(String redirect) {
        var query = FakeOAuthProvider.query(URI.create(redirect).getRawQuery()); assertThat(query).containsKey("code"); return query.get("code");
    }
    private void reason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getDetails()).containsEntry("reason", reason));
    }
    private void flag(boolean enabled) { signup.setEnabled(enabled); }
    private long count(String sql, UUID user) { return database.sql(sql).bind("id", user).map(row -> row.get(0, Long.class)).one().block(TIMEOUT); }
}
