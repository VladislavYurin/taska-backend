package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Credential;
import ru.taska.domain.CredentialType;
import ru.taska.domain.RefreshToken;
import ru.taska.domain.User;
import ru.taska.domain.UserStatus;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {


    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordHashService passwordHashService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Value("${security.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${security.lock-duration-minutes:15}")
    private int lockDurationMinutes;

    @Transactional
    public Mono<AuthResponse> login(String email, String password) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new AuthException("Invalid credentials")))
                .flatMap(user -> validateUserStatus(user)
                        .then(credentialRepository.findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD))
                        .switchIfEmpty(Mono.error(new AuthException("Password not set")))
                        .flatMap(credential -> checkAccountLock(credential)
                                .then(verifyPassword(credential, password, user))
                        )
                )
                .flatMap(credential -> {
                    // Успешная аутентификация - сбрасываем счетчик
                    if (credential.getFailedAttempts() != null && credential.getFailedAttempts() > 0) {
                        return resetFailedAttempts(credential)
                                .then(generateTokens(credential.getUserId()));
                    }
                    return generateTokens(credential.getUserId());
                });
    }

    @Transactional
    public Mono<AuthResponse> refresh(String refreshToken) {
        return refreshTokenService.validateAndRotate(refreshToken)
                .flatMap(response -> userRepository.findById(response.getRefreshToken().getUserId()))
                .switchIfEmpty(Mono.error(new AuthException("User not found")))
                .flatMap(user -> generateAccessTokenOnly(user));
    }

    private Mono<Credential> validateUserStatus(User user) {
        if (user.getStatus() == UserStatus.BLOCKED) {
            return Mono.error(new AuthException("Account is blocked"));
        }
        if (user.getStatus() == UserStatus.INVITED) {
            return Mono.error(new AuthException("Account not activated"));
        }
        return Mono.just(user).then(Mono.empty());
    }

    private Mono<Credential> checkAccountLock(Credential credential) {
        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(Instant.now())) {
            return Mono.error(new AuthException("Account is locked until " + credential.getLockedUntil()));
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

        if (newAttempts >= maxFailedAttempts) {
            lockedUntil = now.plus(lockDurationMinutes, ChronoUnit.MINUTES);
            log.warn("Account locked until {} due to {} failed attempts", lockedUntil, newAttempts);
        }

        credential.setFailedAttempts(newAttempts);
        credential.setLastFailedAt(now);
        credential.setLockedUntil(lockedUntil);

        return credentialRepository.save(credential)
                .then(Mono.error(new AuthException("Invalid credentials")));
    }

    private Mono<Credential> resetFailedAttempts(Credential credential) {
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        return credentialRepository.save(credential);
    }

    private Mono<AuthResponse> generateTokens(UUID userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException("User not found")))
                .flatMap(user -> jwtService.generateAccessToken(user)
                        .zipWith(refreshTokenService.createRefreshToken(user))
                        .zipWith(jwtService.getExpiresIn())
                        .map(tuple -> {
                            String accessToken = tuple.getT1().getT1();
                            String rawRefreshToken = tuple.getT1().getT2();
                            Long expiresIn = tuple.getT2();

                            return AuthResponse.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(rawRefreshToken) // raw token
                                    .expiresIn(expiresIn)
                                    .build();
                        })
                );
    }

    private Mono<AuthResponse> generateAccessTokenOnly(User user) {
        return jwtService.generateAccessToken(user)
                .zipWith(jwtService.getExpiresIn())
                .map(tuple -> AuthResponse.builder()
                        .accessToken(tuple.getT1())
                        .expiresIn(tuple.getT2())
                        .build()
                );
    }

    @lombok.Value
    @lombok.Builder
    public static class AuthResponse {
        String accessToken;
        String refreshToken;
        Long expiresIn;
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }
}
