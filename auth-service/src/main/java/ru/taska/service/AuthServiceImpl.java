package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
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
import ru.taska.security.JwtService;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenService;
import ru.taska.security.config.SecurityProperties;
import ru.taska.util.DataMaskingHelper;
import ru.taska.util.PasswordPolicyValidator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Реализация сервиса аутентификации.
 * <p>Обрабатывает логин с защитой от перебора паролей (блокировка при превышении лимита попыток)
 * и ротацию refresh-токенов.</p>
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
    private final PasswordPolicyValidator passwordPolicyValidator;

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
                .flatMap(user -> credentialRepository
                        .findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD)
                        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Email and password are required")))
                        .flatMap(credential -> {
                            if (credential.getLockedUntil() != null &&
                                    credential.getLockedUntil().isAfter(Instant.now())) {
                                log.warn("Login attempt for locked account: {}", DataMaskingHelper.maskEmail(email));
                                return Mono.error(new DomainException(
                                        DomainStatus.UNAUTHENTICATED,
                                        "Invalid credentials"
                                ));
                            }
                            if (user.getStatus() == UserStatus.BLOCKED || user.getStatus() == UserStatus.INVITED) {
                                log.warn("Login attempt for BLOCKED or INVITED user: {}", DataMaskingHelper.maskEmail(email));
                                return Mono.error(new DomainException(
                                        DomainStatus.UNAUTHENTICATED,
                                        "Invalid credentials"
                                ));
                            }
                            return verifyPassword(credential, password, user)
                                    .flatMap(validCredential ->
                                            resetFailedAttempts(validCredential)
                                                    .then(generateTokens(credential.getUserId()))
                                    );
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

        passwordPolicyValidator.validate(newPassword);

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
     * Проверяет статус пользователя: активен ли, не заблокирован, активирован ли.
     *
     * @param user пользователь
     * @return тот же пользователь, если статус допустим
     * @throws DomainException если статус не позволяет войти
     */
    private Mono<User> validateUserStatus(User user) {
        if (user.getStatus() == UserStatus.BLOCKED) {
            log.warn("Login attempt for blocked user: {}", DataMaskingHelper.maskEmail(user.getEmail()));
            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Account is blocked"));
        }
        if (user.getStatus() == UserStatus.INVITED) {
            log.warn("Login attempt for not activated user: {}", DataMaskingHelper.maskEmail(user.getEmail()));
            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Account not activated"));
        }
        return Mono.just(user);
    }

    private Mono<Credential> checkAccountLock(Credential credential) {
        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(Instant.now())) {
            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                    "Account is locked until " + credential.getLockedUntil()));
        }
        return Mono.just(credential);
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
                    } else {
                        log.warn("Failed login attempt for user: {}", DataMaskingHelper.maskEmail(user.getEmail()));
                        return handleFailedAttempt(credential);
                    }
                });
    }

    /**
     * Обрабатывает неудачную попытку входа: увеличивает счётчик, при необходимости блокирует учётные данные.
     *
     * @param credential учётные данные
     * @return никогда не возвращает успех, всегда ошибка {@link DomainException}
     */
    private Mono<Credential> handleFailedAttempt(Credential credential) {
        int newAttempts = (credential.getFailedAttempts() == null ? 1 : credential.getFailedAttempts() + 1);
        Instant now = Instant.now();
        Instant lockedUntil = null;

        if (newAttempts >= securityProperties.getMaxFailedAttempts()) {
            lockedUntil = now.plus(securityProperties.getLockDuration().toMinutes(), ChronoUnit.MINUTES);
            log.warn("Account locked until {} due to {} failed attempts", lockedUntil, newAttempts);
        }

        credential.setFailedAttempts(newAttempts);
        credential.setLastFailedAt(now);
        credential.setLockedUntil(lockedUntil);

        return credentialRepository.save(credential)
                .then(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));
    }

    /**
     * Сбрасывает счётчик неудачных попыток после успешного входа.
     *
     * @param credential учётные данные
     * @return сохранённые учётные данные с обнулёнными попытками
     */
    private Mono<Credential> resetFailedAttempts(Credential credential) {
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        return credentialRepository.save(credential);
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