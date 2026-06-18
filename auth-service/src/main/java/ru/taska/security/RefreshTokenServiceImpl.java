package ru.taska.security;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.config.JwtProperties;
import ru.taska.util.DataMaskingHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;


@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    private static final String HASH_ALGORITHM = "SHA-256";

    private static final SecureRandom secureRandom = new SecureRandom();

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new DomainException(DomainStatus.INTERNAL, "Hashing algorithm not available");
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    @Transactional
    public Mono<String> createRefreshToken(User user) {
        return Mono.fromCallable(() -> {
            String rawToken = generateRawToken();
            String tokenHash = hashToken(rawToken); // SHA-256, всегда одинаковый для одного токена

            RefreshToken refreshToken = RefreshToken.builder()
                    .userId(user.getId())
                    .tokenHash(tokenHash)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenTtl()))
                    .createdAt(Instant.now())
                    .build();

            log.debug("Creating refresh token for user: {}", user.getId());
            return new TokenPair(refreshToken, rawToken);
        }).flatMap(tokenPair -> refreshTokenRepository.save(tokenPair.refreshToken)
                .thenReturn(tokenPair.rawToken));
    }

    @Override
    @Transactional
    public Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken) {
        String tokenHash = hashToken(rawToken);

        log.debug("Validating and rotating refresh token: {}", DataMaskingHelper.maskJwt(tokenHash));

        return refreshTokenRepository.findValidToken(tokenHash, Instant.now())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED,
                        "Invalid or expired refresh token")))
                .flatMap(existingToken ->
                        // Правильная обработка проверки пользователя
                        userRepository.findById(existingToken.getUserId())
                                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                                        "User not found")))
                                .flatMap(user -> {
                                    if (user.getStatus() == UserStatus.BLOCKED) {
                                        log.debug("Token refresh FAILED due to user with id={} is blocked: token id={} ", user.getId(),existingToken.getId());
                                        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                                                                "invalid credentials"));
                                    }

                                    log.debug("Found existing token with id: {}", existingToken.getId());

                                    String newRawToken = generateRawToken();
                                    String newTokenHash = hashToken(newRawToken);

                                    RefreshToken newRefreshToken = RefreshToken.builder()
                                            .userId(existingToken.getUserId())
                                            .tokenHash(newTokenHash)
                                            .issuedAt(Instant.now())
                                            .expiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenTtl()))
                                            .createdAt(Instant.now())
                                            .build();

                                    return refreshTokenRepository.save(newRefreshToken)
                                            .flatMap(savedNewToken -> {
                                                log.debug("Saved new token with id: {}", savedNewToken.getId());
                                                return refreshTokenRepository.revokeToken(Instant.now(), existingToken.getId())
                                                        .doOnSuccess(updated -> log.debug("Revoked old token, rows updated: {}", updated))
                                                        .thenReturn(new RefreshTokenResponseDto(savedNewToken, newRawToken));
                                            });
                                })
                );
    }

    // Вспомогательный класс
    private static class TokenPair {
        final RefreshToken refreshToken;
        final String rawToken;

        TokenPair(RefreshToken refreshToken, String rawToken) {
            this.refreshToken = refreshToken;
            this.rawToken = rawToken;
        }
    }
}