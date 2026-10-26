package ru.taska.security.pat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonalAccessTokenServiceTest {
    @Mock PersonalAccessTokenRepository tokens;
    @Mock UserRepository users;
    @Mock OutboxEventRepository outbox;
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final String SECRET = "tsk_pat_" + "A".repeat(43);
    private final User owner = User.builder().id(UUID.randomUUID()).email("owner@example.test")
            .displayName("Owner").status(UserStatus.ACTIVE).build();
    private PersonalAccessTokenService service;

    @BeforeEach void prepare() {
        service = new PersonalAccessTokenService(tokens, users, outbox, new PersonalTokenProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC), new JsonMapper());
    }

    @ParameterizedTest
    @ValueSource(strings={"", "junk", "tsk_pat_short", "tsk_other_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "tsk_pat_!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"})
    void malformedTokensDoNotHitDatabase(String secret) {
        StepVerifier.create(service.validate(secret)).expectErrorMatches(this::unauthenticated).verify();
        verifyNoInteractions(tokens, users, outbox);
    }

    @Test void corruptStoredHashCannotAuthenticateAfterLookup() {
        var token = token().tokenHash("f".repeat(64)).build();
        when(tokens.findByTokenHash(DigestUtils.sha256Hex(SECRET))).thenReturn(Mono.just(token));
        StepVerifier.create(service.validate(SECRET)).expectErrorMatches(this::unauthenticated).verify();
        verifyNoInteractions(users, outbox);
        verify(tokens, never()).touchIfStale(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value=UserStatus.class,names={"LOCKED","BLOCKED","INVITED"})
    void patRequiresActiveUserEvenWhereJwtAllowsLocked(UserStatus status) {
        when(tokens.findByTokenHash(DigestUtils.sha256Hex(SECRET))).thenReturn(Mono.just(token().build()));
        when(users.findById(owner.getId())).thenReturn(Mono.just(owner.toBuilder().status(status).build()));
        StepVerifier.create(service.validate(SECRET)).expectErrorMatches(this::unauthenticated).verify();
        verify(tokens, never()).touchIfStale(any(), any(), any());
    }

    @Test void expiryBoundaryAndRevokedTokensRejectBeforeLoadingUser() {
        when(tokens.findByTokenHash(DigestUtils.sha256Hex(SECRET)))
                .thenReturn(Mono.just(token().expiresAt(NOW).build()), Mono.just(token().revokedAt(NOW).build()));
        StepVerifier.create(service.validate(SECRET)).expectErrorMatches(this::unauthenticated).verify();
        StepVerifier.create(service.validate(SECRET)).expectErrorMatches(this::unauthenticated).verify();
        verifyNoInteractions(users, outbox);
    }

    @Test void creationUsesRandomSecretsAndOnlyHashesAndSafeMetadataLeaveTheService() {
        when(users.findByIdForUpdate(owner.getId())).thenReturn(Mono.just(owner));
        when(tokens.countActive(eq(owner.getId()), any())).thenReturn(Mono.just(0L));
        when(tokens.save(any())).thenAnswer(call -> {
            var token = call.getArgument(0, PersonalAccessToken.class);
            token.setId(UUID.randomUUID()); return Mono.just(token);
        });
        when(outbox.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0, OutboxEvent.class)));
        var first = service.create(owner.getId(), " Laptop ", List.of("write","read"), null, "r").block();
        var second = service.create(owner.getId(), "Laptop", List.of("read"), 90, "r").block();
        assertThat(first.secret()).matches("tsk_pat_[A-Za-z0-9_-]{43}").isNotEqualTo(second.secret());
        assertThat(first.token().getName()).isEqualTo("Laptop");
        assertThat(first.token().getScopes()).containsExactly("read", "write");
        assertThat(first.token().getTokenHash()).isEqualTo(DigestUtils.sha256Hex(first.secret()));
        assertThat(first.token().getTokenPrefix()).hasSize(12);
        assertThat(first.toString()).doesNotContain(first.secret(), first.token().getTokenHash());
        assertThat(first.token().toString()).doesNotContain(first.secret(), first.token().getTokenHash());
        var events = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(2)).save(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event -> assertThat(event.getPayload().toString())
                .doesNotContain(first.secret(), first.token().getTokenHash(), second.secret(), second.token().getTokenHash()));
    }

    @Test void invalidNameScopesAndLifetimeNeverLoadUser() {
        StepVerifier.create(service.create(owner.getId(), " ", List.of("read"), 30, "r")).expectError(DomainException.class).verify();
        StepVerifier.create(service.create(owner.getId(), "x", List.of("write"), 30, "r")).expectError(DomainException.class).verify();
        StepVerifier.create(service.create(owner.getId(), "x", List.of("read","admin"), 30, "r")).expectError(DomainException.class).verify();
        StepVerifier.create(service.create(owner.getId(), "x", List.of("read"), 91, "r")).expectError(DomainException.class).verify();
        StepVerifier.create(service.create(owner.getId(), "x", List.of("read"), 0, "r")).expectError(DomainException.class).verify();
        verifyNoInteractions(users, tokens, outbox);
    }

    private PersonalAccessToken.PersonalAccessTokenBuilder token() {
        return PersonalAccessToken.builder().id(UUID.randomUUID()).userId(owner.getId()).tokenHash(DigestUtils.sha256Hex(SECRET))
                .expiresAt(NOW.plusSeconds(100)).scopes(new String[]{"read"});
    }
    private boolean unauthenticated(Throwable error) {
        return error instanceof DomainException domain && domain.getStatus()==DomainStatus.UNAUTHENTICATED;
    }
}
