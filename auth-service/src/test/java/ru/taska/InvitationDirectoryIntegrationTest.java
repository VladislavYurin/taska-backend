package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.auth.admin.inviteuser.v1.*;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.directory.v1.ReactorUserDirectoryServiceGrpc;
import ru.taska.api.directory.v1.SearchUsersRequest;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.repository.UserRepository;
import ru.taska.transport.kafka.OutboxEventScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "jwt.invite-token-ttl=PT2H")
class InvitationDirectoryIntegrationTest extends AbstractIT {
    @MockitoBean
    private OutboxEventScheduler outboxScheduler;
    @Autowired
    private UserRepository users;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private GrpcServerLifecycle grpcServer;
    private ManagedChannel channel;
    private ReactorAdminInviteUserServiceGrpc.ReactorAdminInviteUserServiceStub invitations;
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub auth;
    private ReactorUserDirectoryServiceGrpc.ReactorUserDirectoryServiceStub directory;
    private User admin;
    private static final String PASSWORD = "StrongPassword204!";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @DynamicPropertySource
    static void randomGrpcPort(DynamicPropertyRegistry registry) {
        registry.add("spring.grpc.server.port", () -> "0");
        registry.add("spring.grpc.server.host", () -> "127.0.0.1");
    }

    @BeforeEach
    void prepare() {
        admin = user("admin-" + UUID.randomUUID() + "@example.test", "Admin", UserStatus.ACTIVE, GlobalRole.GLOBAL_ADMIN);
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcServer.getPort()).usePlaintext().build();
        invitations = ReactorAdminInviteUserServiceGrpc.newReactorStub(channel);
        auth = ReactorAuthServiceGrpc.newReactorStub(channel);
        directory = ReactorUserDirectoryServiceGrpc.newReactorStub(channel);
    }

    @AfterEach
    void closeChannel() {
        channel.shutdownNow();
    }

    @Test
    void newInvitationIsCanonicalAndItsTtlPreservesHoursAndTokenRemainsPrivate() {
        String email = "New-" + UUID.randomUUID() + "@Example.Test";
        var result = invite("  " + email + "  ", "", false);
        assertThat(result.getCreated()).isTrue();
        assertThat(result.getDeliveryQueued()).isTrue();
        assertThat(result.getStatus()).isEqualTo(ru.taska.api.common.v1.UserStatus.USER_STATUS_INVITED);
        var saved = users.findById(UUID.fromString(result.getUserId())).block(TIMEOUT);
        assertThat(saved.getEmail()).isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
        assertThat(saved.getDisplayName()).isEqualTo(saved.getEmail().split("@")[0]);
        assertThat(Duration.between(Instant.now(), Instant.parse(result.getExpiresAt())))
                .isBetween(Duration.ofMinutes(119), Duration.ofMinutes(121));
        String token = rawToken(result.getUserId());
        assertThat(token).hasSize(43);
        assertThat(result.toString()).doesNotContain(token);
        assertThat(scalar("SELECT count(*) FROM taska.credentials WHERE user_id=:id", result.getUserId())).isZero();
    }

    @Test
    void concurrentCaseVariantsAndHttpRetriesCreateOneAccountTokenAndEmail() {
        String email = "race-" + UUID.randomUUID() + "@example.test";
        var first = request(email, "Original", false, admin.getId());
        var second = request(email.toUpperCase(java.util.Locale.ROOT), "Other name", false, admin.getId());
        var results = Flux.just(first, second).flatMap(req -> invitations.inviteUser(Mono.just(req))
                .subscribeOn(Schedulers.parallel()), 2).collectList().block(TIMEOUT);
        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(InviteUserResponse::getCreated).count()).isEqualTo(1);
        assertThat(results.stream().map(InviteUserResponse::getUserId).distinct()).hasSize(1);
        String id = results.getFirst().getUserId();
        assertThat(scalar("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id", id)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id", id)).isEqualTo(1);
        var repeat = invite(email, "Do not overwrite", false);
        assertThat(repeat.getUserId()).isEqualTo(id);
        assertThat(repeat.getDeliveryQueued()).isFalse();
        assertThat(users.findById(UUID.fromString(id)).block(TIMEOUT).getDisplayName()).isNotEqualTo("Do not overwrite");
    }

    @Test
    void resendIsThrottledRotatesTheTokenAndActivationIsOneTimeThenLoginWorks() {
        String email = "resend-" + UUID.randomUUID() + "@example.test";
        var initial = invite(email, "Person", false);
        String oldToken = rawToken(initial.getUserId());
        assertCode(() -> invite(email, "", true), Status.Code.RESOURCE_EXHAUSTED);
        ageToken(initial.getUserId(), false);
        var resent = invite(email, "", true);
        assertThat(resent.getCreated()).isFalse();
        assertThat(resent.getDeliveryQueued()).isTrue();
        assertThat(resent.getUserId()).isEqualTo(initial.getUserId());
        String fresh = rawToken(resent.getUserId());
        assertThat(fresh).isNotEqualTo(oldToken);
        assertCode(() -> activate(oldToken, PASSWORD), Status.Code.UNAUTHENTICATED);
        activate(fresh, PASSWORD);
        assertCode(() -> activate(fresh, "OtherPassword204!"), Status.Code.UNAUTHENTICATED);
        var login = auth.login(Mono.just(LoginRequest.newBuilder().setHeader(header())
                .setBody(LoginRequestBody.newBuilder().setEmail(email.toUpperCase(java.util.Locale.ROOT)).setPassword(PASSWORD))
                .build())).block(TIMEOUT);
        assertThat(login.getAccessToken()).isNotBlank();
        assertThat(login.getRefreshToken()).isNotBlank();
        assertThat(scalar("SELECT count(*) FROM taska.credentials WHERE user_id=:id", resent.getUserId())).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='UserActivated'", resent.getUserId()))
                .isEqualTo(1);
    }

    @Test
    void expiredInvitationCanBeReissuedWithoutRecreatingTheAccount() {
        String email = "expired-" + UUID.randomUUID() + "@example.test";
        var initial = invite(email, "Person", false);
        String old = rawToken(initial.getUserId());
        ageToken(initial.getUserId(), true);
        assertCode(() -> activate(old, PASSWORD), Status.Code.UNAUTHENTICATED);
        var next = invite(email, "", false);
        assertThat(next.getUserId()).isEqualTo(initial.getUserId());
        assertThat(next.getDeliveryQueued()).isTrue();
        assertThat(rawToken(next.getUserId())).isNotEqualTo(old);
    }

    @Test
    void activeBlockedAndLockedAccountsAreNeverResetByInvitingThem() {
        for (UserStatus status : List.of(UserStatus.ACTIVE, UserStatus.BLOCKED, UserStatus.LOCKED)) {
            var target = user(status.name() + UUID.randomUUID() + "@example.test", "Existing", status, GlobalRole.USER);
            assertCode(() -> invite(target.getEmail(), "Changed", true), Status.Code.ALREADY_EXISTS);
            assertThat(users.findById(target.getId()).block(TIMEOUT).getStatus()).isEqualTo(status);
            assertThat(scalar("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id", target.getId().toString())).isZero();
        }
    }

    @Test
    void ordinaryActorCannotInviteAndMalformedInputsDoNotWriteAccounts() {
        var actor = user("member-" + UUID.randomUUID() + "@example.test", "Member", UserStatus.ACTIVE, GlobalRole.USER);
        assertCode(() -> invitations.inviteUser(Mono.just(request("new@example.test", "", false, actor.getId()))).block(TIMEOUT),
                Status.Code.PERMISSION_DENIED);
        for (String email : List.of("", "not-an-email", "x@example.test\r\nBcc: victim@example.test")) {
            assertCode(() -> invite(email, "", false), Status.Code.INVALID_ARGUMENT);
        }
        assertCode(() -> invite("valid-" + UUID.randomUUID() + "@example.test", "x".repeat(129), false), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void concurrentActivationsHaveOneWinnerAndOneActivationEvent() {
        var result = invite("activation-" + UUID.randomUUID() + "@example.test", "", false);
        String token = rawToken(result.getUserId());
        var attempts = Flux.range(0, 2).flatMap(i -> auth.setPasswordByToken(Mono.just(activation(token, PASSWORD)))
                .materialize().subscribeOn(Schedulers.parallel()), 2).collectList().block(TIMEOUT);
        assertThat(attempts.stream().filter(signal -> signal.isOnNext()).count()).isEqualTo(1);
        assertThat(attempts.stream().filter(signal -> signal.isOnError()).count()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM taska.outbox_events WHERE aggregate_id=:id AND event_type='UserActivated'", result.getUserId()))
                .isEqualTo(1);
    }

    @Test
    void resendAndActivationSerializeWithoutDeadlockOrPasswordReset() {
        String email = "activation-resend-" + UUID.randomUUID() + "@example.test";
        var result = invite(email, "", false);
        String token = rawToken(result.getUserId());
        ageToken(result.getUserId(), false);
        var results = Flux.<String>merge(
                auth.setPasswordByToken(Mono.just(activation(token, PASSWORD))).map(ignored -> "activated")
                        .onErrorResume(error -> Status.fromThrowable(error).getCode() == Status.Code.UNAUTHENTICATED
                                ? Mono.just("activation-refused") : Mono.error(error)),
                invitations.inviteUser(Mono.just(request(email, "", true, admin.getId()))).map(ignored -> "resent")
                        .onErrorResume(error -> Status.fromThrowable(error).getCode() == Status.Code.ALREADY_EXISTS
                                ? Mono.just("resend-refused") : Mono.error(error)))
                .collectList().block(TIMEOUT);
        assertThat(results).hasSize(2);
        assertThat(java.util.Set.copyOf(results)).isEqualTo(results.contains("activated")
                ? java.util.Set.of("activated", "resend-refused")
                : java.util.Set.of("activation-refused", "resent"));
    }

    @Test
    void weakOrOverlongPasswordsDoNotConsumeTheTokenAndBlockedAccountsCannotActivate() {
        var result = invite("weak-" + UUID.randomUUID() + "@example.test", "", false);
        String token = rawToken(result.getUserId());
        assertCode(() -> activate(token, "weak"), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> activate(token, "Ab1!" + "я".repeat(40)), Status.Code.INVALID_ARGUMENT);
        assertThat(scalar("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id AND used_at IS NULL", result.getUserId())).isEqualTo(1);
        database.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id").bind("id", UUID.fromString(result.getUserId()))
                .fetch().rowsUpdated().block(TIMEOUT);
        assertCode(() -> activate(token, PASSWORD), Status.Code.UNAUTHENTICATED);
        assertThat(scalar("SELECT count(*) FROM taska.credentials WHERE user_id=:id", result.getUserId())).isZero();
    }

    @Test
    void activationOutboxFailureRollsBackPasswordStatusAndTokenConsumption() {
        var result = invite("rollback-" + UUID.randomUUID() + "@example.test", "", false);
        String token = rawToken(result.getUserId());
        installFailureTrigger("UserActivated");
        try {
            assertThatThrownBy(() -> activate(token, PASSWORD)).isInstanceOf(StatusRuntimeException.class);
        } finally {
            removeFailureTrigger();
        }
        assertThat(users.findById(UUID.fromString(result.getUserId())).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(scalar("SELECT count(*) FROM taska.credentials WHERE user_id=:id", result.getUserId())).isZero();
        assertThat(scalar("SELECT count(*) FROM taska.invite_tokens WHERE user_id=:id AND used_at IS NULL", result.getUserId())).isEqualTo(1);
        activate(token, PASSWORD);
    }

    @Test
    void invitationOutboxFailureRollsBackTheAccountAndCanBeRetried() {
        String email = "rollback-create-" + UUID.randomUUID() + "@example.test";
        installFailureTrigger("UserInvited");
        try {
            assertThatThrownBy(() -> invite(email, "", false)).isInstanceOf(StatusRuntimeException.class);
        } finally {
            removeFailureTrigger();
        }
        assertThat(users.findByEmail(email).block(TIMEOUT)).isNull();
        assertThat(invite(email, "", false).getCreated()).isTrue();
    }

    @Test
    void searchIsLiteralCaseInsensitiveBoundedAndNeverReturnsBlockedAccounts() {
        String prefix = "Каталог" + UUID.randomUUID().toString().substring(0, 8);
        var first = user("first-" + UUID.randomUUID() + "@example.test", prefix + " Alpha", UserStatus.ACTIVE, GlobalRole.USER);
        var second = user("second-" + UUID.randomUUID() + "@example.test", prefix + " Beta", UserStatus.INVITED, GlobalRole.USER);
        user("blocked-" + UUID.randomUUID() + "@example.test", prefix + " Gamma", UserStatus.BLOCKED, GlobalRole.USER);
        var page = directory.searchUsers(Mono.just(search(prefix.toUpperCase(java.util.Locale.ROOT), 0, 1))).block(TIMEOUT);
        assertThat(page.getHasMore()).isTrue();
        assertThat(page.getItemsList()).extracting(row -> row.getId()).containsExactly(first.getId().toString());
        var next = directory.searchUsers(Mono.just(search(prefix, 1, 1))).block(TIMEOUT);
        assertThat(next.getHasMore()).isFalse();
        assertThat(next.getItemsList()).extracting(row -> row.getId()).containsExactly(second.getId().toString());
        var literal = user("literal-" + UUID.randomUUID() + "@example.test", "Acme%_!" + prefix, UserStatus.LOCKED, GlobalRole.USER);
        user("other-" + UUID.randomUUID() + "@example.test", "Acme-other" + prefix, UserStatus.ACTIVE, GlobalRole.USER);
        var found = directory.searchUsers(Mono.just(search("Acme%_!" + prefix, 0, 20))).block(TIMEOUT);
        assertThat(found.getItemsList()).extracting(row -> row.getId()).containsExactly(literal.getId().toString());
        for (var request : List.of(search("  ", 0, 20), search("ab", 0, 20), search("ab\0", 0, 20), search(prefix, 0, 51), search(prefix, -1, 20))) {
            assertCode(() -> directory.searchUsers(Mono.just(request)).block(TIMEOUT), Status.Code.INVALID_ARGUMENT);
        }
    }

    @Test
    void missingAvatarDoesNotHideTheUserAndBlockedActorGets403() {
        String name = "Avatar" + UUID.randomUUID();
        var target = user("avatar-" + UUID.randomUUID() + "@example.test", name, UserStatus.ACTIVE, GlobalRole.USER);
        database.sql("INSERT INTO taska.user_avatars (user_id, object_key, file_name, content_type, size_bytes, created_at) "
                        + "VALUES (:id, :key, 'avatar.png', 'image/png', 10, now())")
                .bind("id", target.getId()).bind("key", "missing/" + UUID.randomUUID())
                .fetch().rowsUpdated().block(TIMEOUT);
        var response = directory.searchUsers(Mono.just(search(name, 0, 20).toBuilder()
                .setActorUserId(target.getId().toString()).build())).block(TIMEOUT);
        assertThat(response.getItemsList()).hasSize(1);
        assertThat(response.getItems(0).getId()).isEqualTo(target.getId().toString());
        assertThat(response.getItems(0).hasAvatarUrl()).isFalse();
        database.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id").bind("id", target.getId())
                .fetch().rowsUpdated().block(TIMEOUT);
        assertCode(() -> directory.searchUsers(Mono.just(search(name, 0, 20).toBuilder()
                .setActorUserId(target.getId().toString()).build())).block(TIMEOUT), Status.Code.PERMISSION_DENIED);
    }

    @Test
    void malformedHeadersOrActorCannotReachInvitationBusinessLogic() {
        var valid = request("header-" + UUID.randomUUID() + "@example.test", "", false, admin.getId());
        assertCode(() -> invitations.inviteUser(Mono.just(valid.toBuilder().clearHeader().build())).block(TIMEOUT),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> invitations.inviteUser(Mono.just(valid.toBuilder().setBody(valid.getBody().toBuilder()
                .setActorUserId("invalid")).build())).block(TIMEOUT), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> invitations.inviteUser(Mono.just(valid.toBuilder().setBody(valid.getBody().toBuilder()
                .setActorUserId(UUID.randomUUID().toString())).build())).block(TIMEOUT), Status.Code.PERMISSION_DENIED);
    }

    private User user(String email, String name, UserStatus status, GlobalRole role) {
        return users.save(User.builder().email(email).displayName(name)
                .status(status).globalRole(role).build()).block(TIMEOUT);
    }

    private InviteUserRequest request(String email, String name, boolean resend, UUID actor) {
        return InviteUserRequest.newBuilder().setHeader(header()).setBody(InviteUserRequestBody.newBuilder()
                .setActorUserId(actor.toString()).setEmail(email).setDisplayName(name).setResend(resend)).build();
    }

    private InviteUserResponse invite(String email, String name, boolean resend) {
        return invitations.inviteUser(Mono.just(request(email, name, resend, admin.getId()))).block(TIMEOUT);
    }

    private Header header() {
        return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("test-directory").build();
    }

    private SetPasswordByTokenRequest activation(String token, String password) {
        return SetPasswordByTokenRequest.newBuilder().setHeader(header())
                .setBody(SetPasswordByTokenRequestBody.newBuilder().setToken(token).setNewPassword(password)).build();
    }

    private void activate(String token, String password) {
        auth.setPasswordByToken(Mono.just(activation(token, password))).block(TIMEOUT);
    }

    private SearchUsersRequest search(String query, int page, int size) {
        return SearchUsersRequest.newBuilder().setHeader(header()).setActorUserId(admin.getId().toString())
                .setQuery(query).setPage(page).setPageSize(size).build();
    }

    private long scalar(String sql, String id) {
        return database.sql(sql).bind("id", UUID.fromString(id)).map(row -> row.get(0, Long.class)).one().block(TIMEOUT);
    }

    private String rawToken(String userId) {
        String json = database.sql("SELECT payload::text FROM taska.outbox_events WHERE aggregate_id=:id "
                        + "AND event_type='UserInvited' ORDER BY created_at DESC, id DESC LIMIT 1")
                .bind("id", UUID.fromString(userId)).map(row -> row.get(0, String.class)).one().block(TIMEOUT);
        return JsonMapper.builder().build().readTree(json).get("rawInviteToken").asString();
    }

    private void ageToken(String id, boolean expired) {
        database.sql("UPDATE taska.invite_tokens SET created_at=now()-interval '3 hours', "
                        + "expires_at=now()+ :seconds * interval '1 second' WHERE user_id=:id")
                .bind("seconds", expired ? -1 : 3600).bind("id", UUID.fromString(id)).fetch().rowsUpdated().block(TIMEOUT);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, Status.Code code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StatusRuntimeException.class,
                error -> assertThat(error.getStatus().getCode()).isEqualTo(code));
    }

    private void installFailureTrigger(String type) {
        database.sql("CREATE FUNCTION taska.reject_directory_event() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.event_type='" + type + "' THEN RAISE EXCEPTION 'simulated outbox failure'; END IF; RETURN NEW; END $$")
                .fetch().rowsUpdated().block(TIMEOUT);
        database.sql("CREATE TRIGGER reject_directory_event BEFORE INSERT ON taska.outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION taska.reject_directory_event()")
                .fetch().rowsUpdated().block(TIMEOUT);
    }

    private void removeFailureTrigger() {
        database.sql("DROP TRIGGER reject_directory_event ON taska.outbox_events").fetch().rowsUpdated().block(TIMEOUT);
        database.sql("DROP FUNCTION taska.reject_directory_event()").fetch().rowsUpdated().block(TIMEOUT);
    }
}
