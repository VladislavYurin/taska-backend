package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.UserContext;
import ru.taska.dto.AuthResponseDto;
import ru.taska.entity.Credential;
import ru.taska.entity.CredentialType;
import ru.taska.entity.HashingAlgorithm;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.UserMapper;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.AccountLockService;
import ru.taska.security.JwtService;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenService;
import ru.taska.security.config.SecurityProperties;
import ru.taska.service.AuthService;
import ru.taska.util.DataMaskingHelper;
import ru.taska.util.GlobalRoleMapper;
import ru.taska.util.JwtValidator;
import ru.taska.util.PasswordValidator;
import ru.taska.util.UserStatusMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Реализация сервиса аутентификации.
 * <p>Обрабатывает логин с защитой от перебора паролей (блокировка при превышении лимита попыток)
 * и ротацию refresh-токенов.</p>
 *
 * <p>Поведение при блокировке (ADR: см. docs/adr-lock-behavior.md):</p>
 * <ul>
 *   <li>{@code LOCKED} — временный лок за неудачные попытки входа. Источник правды —
 *       {@code users.locked_until}. {@code LOCKED} <b>не</b> отзывает уже выпущенный
 *       access-токен: легитимная сессия на другом устройстве не должна страдать от
 *       перебора пароля третьей стороной. Лок снимается лениво на {@code login}
 *       (через {@link AccountLockService#resolveLockState(User)}) и на {@code refresh}
 *       (через {@link RefreshTokenService#validateAndRotate(String)}). {@code refresh}
 *       <b>не</b> выдаёт новые токены, пока аккаунт {@code LOCKED}.</li>
 *   <li>{@code BLOCKED} и {@code INVITED} — терминальные статусы (админский бан и
 *       незавершённая активация). Отзывают уже выпущенный access-токен немедленно:
 *       {@link #validateAccessToken(String)} читает статус из БД на каждом запросе.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHashService passwordHashService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final SecurityProperties securityProperties;
    private final InviteTokenRepository inviteTokenRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final UserMapper userMapper;
    private final PasswordValidator passwordValidator;
    private final JwtValidator jwtValidator;
    private final AccountLockService accountLockService;

    /**
     * Транзакционный оператор с propagation REQUIRES_NEW.
     *
     * <p>Явно указан через {@link Qualifier}, потому что в контексте два бина
     * {@link TransactionalOperator}:
     * <ul>
     *   <li>{@code transactionalOperator} — PROPAGATION_REQUIRED;</li>
     *   <li>{@code requiresNewTransactionalOperator} — PROPAGATION_REQUIRES_NEW.</li>
     * </ul>
     *
     * <p>{@code @Qualifier} копируется Lombok'ом с поля на параметр конструктора
     * благодаря {@code lombok.config} в корне модуля.
     */
    @Qualifier("requiresNewTransactionalOperator")
    private final TransactionalOperator requiresNewTransactionalOperator;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Mono<AuthResponseDto> login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Email and password are required"));
        }

        String normalizedEmail = email.trim().toLowerCase();

        return userRepository.findByEmail(normalizedEmail)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")))
                .flatMap(user ->
                        credentialRepository
                                .findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD)
                                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")))
                                .flatMap( credential ->{

                                    if (user.getStatus() == UserStatus.BLOCKED || user.getStatus() == UserStatus.INVITED) {
                                        log.warn("Login attempt for {} user: {}", user.getStatus(), DataMaskingHelper.maskEmail(email));
                                        return Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials"));
                                    }
                                    return accountLockService.resolveLockState(user)
                                            .flatMap(refreshed -> {
                                                if (refreshed.getStatus() == UserStatus.LOCKED) {
                                                    log.warn("Login attempt for LOCKED user: {}", DataMaskingHelper.maskEmail(email));
                                                    return Mono.error(new DomainException(
                                                            DomainStatus.PERMISSION_DENIED,
                                                            "Account is locked until " + refreshed.getLockedUntil() + ". Try again later."));
                                                }
                                                return authenticate(refreshed, credential, password);
                                            });
                                })
                );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Mono<AuthResponseDto> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Refresh token cannot be blank"));
        }

        // Статус и локаут проверяются внутри validateAndRotate — до создания нового токена,
        // чтобы отказ не сжигал старый refresh.
        return refreshTokenService.validateAndRotate(refreshToken)
                .flatMap(rotationResult -> {
                    String newRawRefreshToken = rotationResult.getRawToken();
                    UUID userId = rotationResult.getRefreshToken().getUserId();

                    return userRepository.findById(userId)
                            .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                            .flatMap(user -> generateAccessTokenOnlySetRefreshToken(user, newRawRefreshToken));
                });
    }

    @Override
    @Transactional
    public Mono<Void> setPasswordByToken(String requestId, String token, String newPassword) {
        if (token == null || token.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Token required"));
        }

        passwordValidator.validate(newPassword);
        String tokenHash = hashToken(token);

        log.debug("setPasswordByToken: processing token hash: {}", DataMaskingHelper.maskJwt(tokenHash));

        return inviteTokenRepository.markTokenAsUsedIfValid(tokenHash)
                .flatMap(updatedRows -> {
                    if (updatedRows == 0) {
                        log.debug("setPasswordByToken failed: invalid or expired token, hash: {}", DataMaskingHelper.maskJwt(tokenHash));
                        return Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired token"));
                    }
                    return inviteTokenRepository.findByTokenHash(tokenHash)
                            .flatMap(inviteToken -> userRepository.findById(inviteToken.getUserId())
                                    .filter(user -> user.getStatus() == UserStatus.INVITED)
                                    .switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired token")))
                                    .flatMap(user -> credentialRepository
                                            .findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD)
                                            .defaultIfEmpty(createEmptyCredential(user.getId()))
                                            .flatMap(credential -> {
                                                credential.setSecretHash(passwordHashService.encode(newPassword, HashingAlgorithm.BCRYPT));
                                                return credentialRepository.save(credential);
                                            })
                                            .then(Mono.defer(() -> {
                                                user.setStatus(UserStatus.ACTIVE);
                                                log.info("User id={} activated successfully via invite token", user.getId());
                                                return userRepository.save(user)
                                                        .then(Mono.fromCallable(() -> userMapper.buildUserActivatedOutboxEvent(user, requestId)))
                                                        .flatMap(outboxEventRepository::save);
                                            }))
                                    ));


                })
                .then();
    }

    /**
     * {@inheritDoc}
     * <p>Читает статус пользователя из БД на каждом вызове.</p>
     *
     * <p>Отзывает уже выпущенный access-токен немедленно для терминальных статусов:</p>
     * <ul>
     *   <li>{@code BLOCKED} — админский бан;</li>
     *   <li>{@code INVITED} — аккаунт не активирован.</li>
     * </ul>
     *
     * <p>Для {@code LOCKED} access-токен <b>не</b> отзывается: лок — это анти-брутфорс
     * на форме входа, легитимная сессия на другом устройстве не должна страдать.
     * Лок снимется лениво на ближайшем {@code login} или {@code refresh}.</p>
     */
    @Override
    public Mono<UserContext> validateAccessToken(String accessToken) {
        return jwtValidator.validate(accessToken)
                .flatMap(claims -> {
                    UUID userId = UUID.fromString(claims.getSubject());
                    return userRepository.findById(userId)
                            .switchIfEmpty(Mono.error(() -> {
                                log.debug("User not found, userId: {}", userId);
                                return new DomainException(DomainStatus.UNAUTHENTICATED, "User not found");
                            }))
                            .flatMap(this::validateUserStatus)
                            .map(this::buildUserContext);
                });
        }

    private Credential createEmptyCredential(UUID userId) {
        return Credential.builder()
                .userId(userId)
                .credentialType(CredentialType.PASSWORD)
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .build();
    }

    private String hashToken(String rawToken) {
        return DigestUtils.sha256Hex(rawToken);
    }

    /**
     * Проверяет статус пользователя для доступа по access-токену.
     *
     * <p>Режет только терминальные статусы: {@code BLOCKED} (админский бан) и
     * {@code INVITED} (не завершена активация). {@code LOCKED} не режется — лок
     * это анти-брутфорс формы входа; access-токен легитимной сессии продолжает
     * работать.
     */
    private Mono<User> validateUserStatus(User user) {
        return switch (user.getStatus()) {
            case BLOCKED -> {
                log.warn("User is blocked, userId: {}", user.getId());
                yield Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "User not found or inactive"));
            }
            case INVITED -> {
                log.warn("User is not activated, userId: {}", user.getId());
                yield Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "User not found or inactive"));
            }
            default -> Mono.just(user);
        };
    }

    private UserContext buildUserContext(User user) {
        return UserContext.newBuilder()
                .setUserId(user.getId().toString())
                .setLogin(user.getLogin())
                .setEmail(user.getEmail())
                .setDisplayName(user.getDisplayName())
                .setStatus(UserStatusMapper.toProtoStatus(user.getStatus()))
                .setGlobalRole(GlobalRoleMapper.toProtoGlobalRole(user.getGlobalRole()))
                .build();
    }

    private Mono<AuthResponseDto> authenticate(User user, Credential credential, String password) {
        return verifyPassword(credential, password, user)
                .flatMap(valid -> resetFailedAttempts(user,valid)
                        .then(generateTokens(user.getId())));
    }
    /**
     * Сверяет переданный пароль с хэшем в учётных данных.
     *
     * @param credential  учётные данные
     * @param rawPassword пароль в открытом виде
     * @param user        пользователь (для логирования)
     * @return учётные данные при успешной проверке, иначе обработка неудачной попытки
     */
    private Mono<Credential> verifyPassword(Credential credential, String rawPassword, User user) {
        return passwordHashService.matches(credential, rawPassword)
                .flatMap(matches -> {
                    if (matches) {
                        log.info("Successful login for user: {}", DataMaskingHelper.maskEmail(user.getEmail()));
                        return Mono.just(credential);
                    }
                    else {
                        log.warn("Failed login attempt for user: {}", DataMaskingHelper.maskEmail(user.getEmail()));
                        return handleFailedAttempt(credential,user);
                    }
                });
    }

    /**
     * Обрабатывает неудачную попытку входа: увеличивает счётчик, при необходимости
     * переводит аккаунт в {@code LOCKED} и проставляет {@code lockedUntil}.
     *
     * @param credential учётные данные
     * @param user       пользователь
     * @return никогда не возвращает успех, всегда ошибка {@link DomainException}
     */
    private Mono<Credential> handleFailedAttempt(Credential credential,User user) {
        int newAttempts = credential.getFailedAttempts() + 1;

        Instant now = Instant.now();

        boolean shouldLock = newAttempts >= securityProperties.getMaxFailedAttempts();

        Instant lockedUntil = shouldLock
                ? now.plus(securityProperties.getLockDuration())
                : null;

        credential.setFailedAttempts(newAttempts);
        credential.setLastFailedAt(now);

        return requiresNewTransactionalOperator.transactional(
                credentialRepository.save(credential)
                        .flatMap(savedCredential -> {
                            if (shouldLock && (user.getStatus() != UserStatus.LOCKED)) {
                                user.setStatus(UserStatus.LOCKED);
                                user.setLockedUntil(lockedUntil);
                                return userRepository.save(user)
                                        .doOnSuccess( savedUser->
                                                log.warn("Account locked until {} due to {} failed attempts", lockedUntil, newAttempts)
                                        )
                                        .thenReturn(savedCredential);

                            }
                            return Mono.just(savedCredential);
                        })
                )
                .then(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));
    }

    /**
     * Сбрасывает счётчик неудачных попыток и лок после успешного входа.
     *
     * @param user       пользователь
     * @param credential учётные данные
     * @return сохранённые учётные данные
     */
    private Mono<Void> resetFailedAttempts(User user, Credential credential) {
        credential.setFailedAttempts(0);

        Mono<Void> saveCredential = credentialRepository.save(credential).then();

        boolean needUserSave = user.getStatus() == UserStatus.LOCKED || user.getLockedUntil() != null;

        if (needUserSave) {
            user.setStatus(UserStatus.ACTIVE);
            user.setLockedUntil(null);
            return saveCredential
                    .then(userRepository.save(user))
                    .then();
        }
        return saveCredential;
    }

    /**
     * Генерирует новую пару access + refresh токенов для пользователя.
     *
     * @param userId идентификатор пользователя
     * @return DTO с токенами
     */
    private Mono<AuthResponseDto> generateTokens(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> jwtService.generateAccessToken(user)
                        .zipWith(refreshTokenService.createRefreshToken(user))
                        .zipWith(jwtService.getExpiresIn())
                        .map(tuple -> {
                            String accessToken = tuple.getT1().getT1();
                            String rawRefreshToken = tuple.getT1().getT2();
                            Long expiresIn = tuple.getT2();

                            return AuthResponseDto.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(rawRefreshToken)
                                    .expiresIn(expiresIn)
                                    .build();
                        })
                );
    }

    /**
     * Генерирует только новый access-токен, используя уже существующий (новый) refresh-токен.
     * Применяется при ротации refresh-токена.
     *
     * @param user            пользователь
     * @param newRefreshToken уже созданный сырой refresh-токен
     * @return DTO с токенами
     */
    private Mono<AuthResponseDto> generateAccessTokenOnlySetRefreshToken(User user, String newRefreshToken) {
        return jwtService.generateAccessToken(user)
                .zipWith(jwtService.getExpiresIn())
                .map(tuple -> AuthResponseDto.builder()
                        .accessToken(tuple.getT1())
                        .refreshToken(newRefreshToken)
                        .expiresIn(tuple.getT2())
                        .build()
                );
    }
}