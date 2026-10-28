package ru.taska.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.dto.AuthResponseDto;
import ru.taska.entity.*;
import ru.taska.repository.*;
import ru.taska.security.JwtService;
import ru.taska.security.RefreshTokenService;
import ru.taska.service.AccountLockService;
import ru.taska.mapper.UserMapper;
import ru.taska.util.DisplayNames;
import java.time.Clock;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OAuthAccountService {
    private final UserRepository users;
    private final CredentialRepository credentials;
    private final InviteTokenRepository invitations;
    private final OutboxEventRepository outbox;
    private final UserMapper mapper;
    private final DatabaseClient database;
    private final AccountLockService locks;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final Clock clock;
    private final ru.taska.config.props.SignupProperties signup;

    /** Read-only callback check; account state and flag are checked again under locks at exchange/signup. */
    public Mono<Void> check(OAuthIdentity identity) { return check(identity, true); }
    public Mono<Void> checkSignIn(OAuthIdentity identity) { return check(identity, false); }
    private Mono<Void> check(OAuthIdentity identity, boolean signupPolicy) {
        return binding(identity).flatMap(credential -> users.findById(credential.getUserId()))
                .switchIfEmpty(Mono.defer(() -> users.findByEmail(identity.email())))
                .doOnNext(this::checkStatus).map(ignored -> true)
                .switchIfEmpty(Mono.defer(() -> !signupPolicy || signup.isEnabled() ? Mono.just(true) : Mono.error(OAuthErrors.failure("SIGNUP_DISABLED")))).then();
    }
    @Transactional
    public Mono<Optional<AuthResponseDto>> signIn(OAuthIdentity identity, String displayName, String requestId) {
        return resolve(identity, displayName, requestId, true).flatMap(user -> user.isPresent()
                ? session(user.get()).map(Optional::of) : Mono.just(Optional.empty()));
    }
    /** Browser sign-in for the authorization server: link/activate existing accounts, never issue JWT/refresh. */
    @Transactional
    public Mono<User> signInSubject(OAuthIdentity identity, String requestId) {
        return resolve(identity, null, requestId, false).flatMap(user -> user.map(Mono::just)
                .orElseGet(() -> Mono.error(OAuthErrors.failure("SIGNUP_REQUIRED"))));
    }
    private Mono<Optional<User>> resolve(OAuthIdentity identity, String displayName, String requestId, boolean allowCreate) {
        return Mono.defer(() -> {
            String name = displayName == null ? null : DisplayNames.normalize(displayName);
            return mutex("oauth:" + identity.provider() + ":" + identity.subject()).then(mutex(identity.email()))
                    .then(Mono.defer(() -> binding(identity).flatMap(credential -> users.findByIdForUpdate(credential.getUserId())
                                    .switchIfEmpty(Mono.error(OAuthErrors.failure("PROVIDER_UNAVAILABLE"))))
                            .flatMap(user -> existing(user, identity, false, requestId))
                            .switchIfEmpty(Mono.defer(() -> users.findByCanonicalEmailForUpdate(identity.email())
                                    .flatMap(user -> existing(user, identity, true, requestId))
                                    .switchIfEmpty(Mono.defer(() -> {
                                        if (!allowCreate) return Mono.just(Optional.empty());
                                        if (!signup.isEnabled()) return Mono.error(OAuthErrors.failure("SIGNUP_DISABLED"));
                                        if (name == null) return Mono.just(Optional.empty());
                                        return users.save(User.builder().email(identity.email()).displayName(name).createdVia(UserCreatedVia.OAUTH)
                                                        .status(UserStatus.ACTIVE).globalRole(ru.taska.domain.GlobalRole.USER).build())
                                                .flatMap(user -> link(user, identity).then(activated(user, requestId)).thenReturn(user)).map(Optional::of);
                                    }))))));
        });
    }
    private Mono<Optional<User>> existing(User user, OAuthIdentity identity, boolean link, String requestId) {
        return locks.resolveLockState(user).flatMap(current -> {
            checkStatus(current);
            Mono<User> active;
            if (current.getStatus() == UserStatus.INVITED) {
                current.setStatus(UserStatus.ACTIVE);
                active = users.save(current).flatMap(saved -> invitations.revokeUnusedForUser(saved.getId())
                        .then(activated(saved, requestId)).thenReturn(saved));
            } else active = Mono.just(current);
            return active.flatMap(saved -> (link ? link(saved, identity) : Mono.<Void>empty()).thenReturn(saved)).map(Optional::of);
        });
    }
    private void checkStatus(User user) {
        if (user.getStatus() == UserStatus.BLOCKED) throw OAuthErrors.failure("ACCOUNT_BLOCKED");
        if (user.getStatus() == UserStatus.LOCKED && user.getLockedUntil() != null && user.getLockedUntil().isAfter(clock.instant()))
            throw OAuthErrors.locked(user.getLockedUntil());
    }
    private Mono<Void> activated(User user, String requestId) { return outbox.save(mapper.buildUserActivatedOutboxEvent(user, requestId)).then(); }
    private Mono<Void> link(User user, OAuthIdentity identity) {
        return credentials.save(Credential.builder().userId(user.getId()).credentialType(type(identity.provider()))
                .provider(identity.provider()).subject(identity.subject()).failedAttempts(0).build()).then();
    }
    private Mono<Credential> binding(OAuthIdentity identity) {
        return credentials.findExternal(type(identity.provider()), identity.provider(), identity.subject());
    }
    private CredentialType type(String provider) { return provider.equals("github") ? CredentialType.OAUTH : CredentialType.OIDC; }
    private Mono<Void> mutex(String value) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))").bind("email", value).map((row, metadata) -> true).one().then();
    }
    private Mono<AuthResponseDto> session(User user) {
        return jwt.generateAccessToken(user).zipWith(refresh.createRefreshToken(user)).zipWith(jwt.getExpiresIn())
                .map(value -> AuthResponseDto.builder().accessToken(value.getT1().getT1()).refreshToken(value.getT1().getT2()).expiresIn(value.getT2()).build());
    }
}
