package ru.taska;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.*;
import ru.taska.transport.kafka.OutboxEventScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ProfileNamesAndInvitationsIT extends AbstractIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String PASSWORD = "StrongPassword204!";
    @MockitoBean OutboxEventScheduler outboxScheduler;
    @Autowired UserRepository users;
    @Autowired org.springframework.r2dbc.core.DatabaseClient database;
    @Autowired InviteTokenRepository tokens;
    @Autowired AuthService auth;
    @Autowired ProfileService profiles;
    @Autowired UserDirectoryService directory;
    @Autowired UserDisplayNameService adminNames;

    @Test
    void previewIsRepeatableAndSignupActivationAtomicallyStoresOptionalName() {
        User user = user(UserStatus.INVITED, GlobalRole.USER, UserCreatedVia.SIGNUP);
        String raw = invitation(user, Instant.now().plusSeconds(3600));
        var first = auth.previewInvitation(raw).block(TIMEOUT);
        assertThat(first.email()).isEqualTo(user.getEmail());
        assertThat(first.displayName()).isEqualTo("Original");
        assertThat(first.kind()).isEqualTo("SIGNUP");
        assertThat(auth.previewInvitation(raw).block(TIMEOUT)).isEqualTo(first);
        assertThat(tokens.findByUserId(user.getId()).block(TIMEOUT).getUsedAt()).isNull();

        auth.setPasswordByToken("activation", raw, PASSWORD, "  Accepted Name  ").block(TIMEOUT);
        User saved = users.findById(user.getId()).block(TIMEOUT);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getDisplayName()).isEqualTo("Accepted Name");
        assertThat(saved.getCreatedVia()).isEqualTo(UserCreatedVia.SIGNUP);
        assertThat(tokens.findByUserId(user.getId()).block(TIMEOUT).getUsedAt()).isNotNull();
        rejected(() -> auth.previewInvitation(raw).block(TIMEOUT), DomainStatus.UNAUTHENTICATED);
    }

    @Test
    void invalidNameAndWeakPasswordLeaveInvitationUsableAndOmittedNamePreservesIt() {
        User user = user(UserStatus.INVITED, GlobalRole.USER, UserCreatedVia.INVITATION);
        String raw = invitation(user, Instant.now().plusSeconds(3600));
        assertThat(auth.previewInvitation(raw).block(TIMEOUT).kind()).isEqualTo("INVITATION");
        assertThatThrownBy(() -> auth.setPasswordByToken("invalid", raw, PASSWORD, "bad\nname").block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getDetails()).containsEntry("reason", "DISPLAY_NAME_INVALID"));
        rejected(() -> auth.setPasswordByToken("weak", raw, "weak", "New").block(TIMEOUT), DomainStatus.INVALID_ARGUMENT);
        assertThat(tokens.findByUserId(user.getId()).block(TIMEOUT).getUsedAt()).isNull();
        User unchanged = users.findById(user.getId()).block(TIMEOUT);
        assertThat(unchanged.getDisplayName()).isEqualTo("Original");
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.INVITED);
        auth.setPasswordByToken("valid", raw, PASSWORD).block(TIMEOUT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo("Original");
    }

    @Test
    void expiredUnknownAndUsedInvitationsShareUnauthenticatedPreviewFailure() {
        User expired = user(UserStatus.INVITED, GlobalRole.USER, UserCreatedVia.INVITATION);
        String expiredRaw = invitation(expired, Instant.now().minusSeconds(1));
        User used = user(UserStatus.INVITED, GlobalRole.USER, UserCreatedVia.INVITATION);
        String usedRaw = invitation(used, Instant.now().plusSeconds(3600));
        tokens.markTokenAsUsedIfValid(DigestUtils.sha256Hex(usedRaw)).block(TIMEOUT);
        for (String raw : List.of(expiredRaw, usedRaw, "unknown", "")) {
            rejected(() -> auth.previewInvitation(raw).block(TIMEOUT), DomainStatus.UNAUTHENTICATED);
        }
        assertThat(tokens.findByUserId(expired.getId()).block(TIMEOUT).getUsedAt()).isNull();
    }

    @Test
    void profileUpdateChangesOnlyNameAndIsImmediatelyVisibleInBatchAndDirectory() {
        User user = user(UserStatus.LOCKED, GlobalRole.GLOBAL_ADMIN, UserCreatedVia.OAUTH);
        String name = "Directory" + UUID.randomUUID().toString().substring(0, 8);
        Instant previousUpdate = database.sql("UPDATE taska.users SET updated_at=now()-interval '1 day' WHERE id=:id RETURNING updated_at")
                .bind("id", user.getId()).map(row -> row.get("updated_at", Instant.class)).one().block(TIMEOUT);
        profiles.updateMyProfile(user.getId(), "  " + name + "  ").block(TIMEOUT);
        User saved = users.findById(user.getId()).block(TIMEOUT);
        assertThat(saved.getDisplayName()).isEqualTo(name);
        assertThat(saved.getEmail()).isEqualTo(user.getEmail());
        assertThat(saved.getStatus()).isEqualTo(user.getStatus());
        assertThat(saved.getGlobalRole()).isEqualTo(user.getGlobalRole());
        assertThat(saved.getCreatedVia()).isEqualTo(UserCreatedVia.OAUTH);
        assertThat(saved.getUpdatedAt()).isAfter(previousUpdate);
        assertThat(profiles.getUserDetailsByIds(List.of(user.getId())).single().block(TIMEOUT).displayName()).isEqualTo(name);
        assertThat(directory.search(user.getId(), name, 0, 20).block(TIMEOUT).getItemsList())
                .extracting(item -> item.getDisplayName()).containsExactly(name);
        rejected(() -> profiles.updateMyProfile(user.getId(), " ").block(TIMEOUT), DomainStatus.INVALID_ARGUMENT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo(name);
        String unicodeLimit = "😀".repeat(128);
        profiles.updateMyProfile(user.getId(), unicodeLimit).block(TIMEOUT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo(unicodeLimit);
        rejected(() -> profiles.updateMyProfile(user.getId(), unicodeLimit + "😀").block(TIMEOUT), DomainStatus.INVALID_ARGUMENT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo(unicodeLimit);
    }

    @Test
    void adminNameMutationChecksDatabaseRoleAndReturnsPreviousNameWithoutChangingStatus() {
        User actor = user(UserStatus.ACTIVE, GlobalRole.GLOBAL_ADMIN, UserCreatedVia.INVITATION);
        User target = user(UserStatus.BLOCKED, GlobalRole.USER, UserCreatedVia.INVITATION);
        var change = adminNames.updateByAdmin(actor.getId(), target.getId(), " New Name ").block(TIMEOUT);
        assertThat(change.previousDisplayName()).isEqualTo("Original");
        assertThat(change.displayName()).isEqualTo("New Name");
        assertThat(users.findById(target.getId()).block(TIMEOUT).getStatus()).isEqualTo(UserStatus.BLOCKED);
        rejected(() -> adminNames.updateByAdmin(target.getId(), actor.getId(), "Hacked").block(TIMEOUT), DomainStatus.PERMISSION_DENIED);
        rejected(() -> adminNames.updateByAdmin(actor.getId(), UUID.randomUUID(), "Missing").block(TIMEOUT), DomainStatus.NOT_FOUND);
    }

    @Test
    void activationFailureRollsBackNameAndTokenAndCanBeRetried() {
        User user = user(UserStatus.INVITED, GlobalRole.USER, UserCreatedVia.SIGNUP);
        String raw = invitation(user, Instant.now().plusSeconds(3600));
        database.sql("CREATE FUNCTION taska.reject_profile_activation() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.event_type='UserActivated' THEN RAISE EXCEPTION 'simulated activation failure'; END IF; RETURN NEW; END $$")
                .fetch().rowsUpdated().block(TIMEOUT);
        database.sql("CREATE TRIGGER reject_profile_activation BEFORE INSERT ON taska.outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION taska.reject_profile_activation()")
                .fetch().rowsUpdated().block(TIMEOUT);
        try {
            assertThatThrownBy(() -> auth.setPasswordByToken("failed", raw, PASSWORD, "New Name").block(TIMEOUT))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            database.sql("DROP TRIGGER reject_profile_activation ON taska.outbox_events").fetch().rowsUpdated().block(TIMEOUT);
            database.sql("DROP FUNCTION taska.reject_profile_activation()").fetch().rowsUpdated().block(TIMEOUT);
        }
        User unchanged = users.findById(user.getId()).block(TIMEOUT);
        assertThat(unchanged.getDisplayName()).isEqualTo("Original");
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(tokens.findByUserId(user.getId()).block(TIMEOUT).getUsedAt()).isNull();
        Long credentials = database.sql("SELECT count(*) FROM taska.credentials WHERE user_id=:id")
                .bind("id", user.getId()).map(row -> row.get(0, Long.class)).one().block(TIMEOUT);
        assertThat(credentials).isZero();
        auth.setPasswordByToken("retry", raw, PASSWORD, "New Name").block(TIMEOUT);
        assertThat(users.findById(user.getId()).block(TIMEOUT).getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void databaseDefaultsLegacyAccountsToInvitationAndRejectsUnknownOrigin() {
        UUID id = database.sql("INSERT INTO taska.users(email,display_name,status,global_role) "
                        + "VALUES(:email,'Legacy','INVITED','USER') RETURNING id")
                .bind("email", UUID.randomUUID() + "@example.test").map(row -> row.get("id", UUID.class)).one().block(TIMEOUT);
        assertThat(users.findById(id).block(TIMEOUT).getCreatedVia()).isEqualTo(UserCreatedVia.INVITATION);
        assertThatThrownBy(() -> database.sql("UPDATE taska.users SET created_via='UNKNOWN' WHERE id=:id")
                .bind("id", id).fetch().rowsUpdated().block(TIMEOUT)).isInstanceOf(RuntimeException.class);
        assertThat(users.findById(id).block(TIMEOUT).getCreatedVia()).isEqualTo(UserCreatedVia.INVITATION);
    }

    private User user(UserStatus status, GlobalRole role, UserCreatedVia via) {
        return users.save(User.builder().email(UUID.randomUUID() + "@example.test").displayName("Original")
                .status(status).globalRole(role).createdVia(via).build()).block(TIMEOUT);
    }
    private String invitation(User user, Instant expiry) {
        String raw = UUID.randomUUID().toString();
        database.sql("INSERT INTO taska.invite_tokens(user_id,token_hash,created_at,expires_at) VALUES(:user,:hash,:created,:expiry)")
                .bind("user", user.getId()).bind("hash", DigestUtils.sha256Hex(raw))
                .bind("created", expiry.minusSeconds(3600)).bind("expiry", expiry)
                .fetch().rowsUpdated().block(TIMEOUT);
        return raw;
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, DomainStatus status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getStatus()).isEqualTo(status));
    }
}
