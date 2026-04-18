package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.dto.AuthResponseDto;
import ru.taska.entity.*;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.JwtServiceImpl;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenServiceImpl;
import ru.taska.security.config.SecurityProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHashService passwordHashService;
    private final JwtServiceImpl jwtServiceImpl;
    private final RefreshTokenServiceImpl refreshTokenServiceImpl;
    private final SecurityProperties securityProperties;

    @Override
    @Transactional
    public Mono<AuthResponseDto> login(String email, String password) {
        if (email == null || email.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Email cannot be blank"));
        }
        if (password == null || password.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Password cannot be blank"));
        }

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(this::validateUserStatus)
                .flatMap(user -> credentialRepository
                        .findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD)
                        .switchIfEmpty(Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                "Password not set for user")))
                        .flatMap(credential -> checkAccountLock(credential)
                                .flatMap(validCredential -> verifyPassword(validCredential, password, user)) // Исправлено: используем flatMap вместо then
                        )
                )
                .flatMap(credential -> {
                    if (credential.getFailedAttempts() != null && credential.getFailedAttempts() > 0) {
                        return resetFailedAttempts(credential)
                                .then(generateTokens(credential.getUserId()));
                    }
                    return generateTokens(credential.getUserId());
                });
    }

    @Override
    @Transactional
    public Mono<AuthResponseDto> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Refresh token cannot be blank"));
        }

        return refreshTokenServiceImpl.validateAndRotate(refreshToken)
                .flatMap(rotationResult -> {
                    String newRawRefreshToken = rotationResult.getRawToken();
                    UUID userId = rotationResult.getRefreshToken().getUserId();

                    return userRepository.findById(userId)
                            .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                            .flatMap(user -> generateAccessTokenOnlySetRefreshToken(user, newRawRefreshToken));
                });
    }

    private Mono<User> validateUserStatus(User user) {
        if (user.getStatus() == UserStatus.BLOCKED) {
            log.warn("Login attempt for blocked user: {}", user.getEmail());
            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Account is blocked"));
        }
        if (user.getStatus() == UserStatus.INVITED) {
            log.warn("Login attempt for not activated user: {}", user.getEmail());
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

    private Mono<Credential> verifyPassword(Credential credential, String rawPassword, User user) {
        return passwordHashService.matches(credential, rawPassword)
                .flatMap(matches -> {
                    if (matches) {
                        log.info("Successful login for user: {}", user.getEmail());
                        return Mono.just(credential);
                    } else {
                        log.warn("Failed login attempt for user: {}", user.getEmail());
                        return handleFailedAttempt(credential);
                    }
                });
    }

    private Mono<Credential> handleFailedAttempt(Credential credential) {
        int newAttempts = (credential.getFailedAttempts() == null ? 1 : credential.getFailedAttempts() + 1);
        Instant now = Instant.now();
        Instant lockedUntil = null;

        if (newAttempts >= securityProperties.getMaxFailedAttempts()) {
            lockedUntil = now.plus(securityProperties.getLockDurationMinutes(), ChronoUnit.MINUTES);
            log.warn("Account locked until {} due to {} failed attempts", lockedUntil, newAttempts);
        }

        credential.setFailedAttempts(newAttempts);
        credential.setLastFailedAt(now);
        credential.setLockedUntil(lockedUntil);

        return credentialRepository.save(credential)
                .then(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));
    }

    private Mono<Credential> resetFailedAttempts(Credential credential) {
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        return credentialRepository.save(credential);
    }

    private Mono<AuthResponseDto> generateTokens(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(user -> jwtServiceImpl.generateAccessToken(user)
                        .zipWith(refreshTokenServiceImpl.createRefreshToken(user))
                        .zipWith(jwtServiceImpl.getExpiresIn())
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

    private Mono<AuthResponseDto> generateAccessTokenOnlySetRefreshToken(User user, String newRefreshToken) {
        return jwtServiceImpl.generateAccessToken(user)
                .zipWith(jwtServiceImpl.getExpiresIn())
                .map(tuple -> AuthResponseDto.builder()
                        .accessToken(tuple.getT1())
                        .refreshToken(newRefreshToken)
                        .expiresIn(tuple.getT2())
                        .build()
                );
    }
}