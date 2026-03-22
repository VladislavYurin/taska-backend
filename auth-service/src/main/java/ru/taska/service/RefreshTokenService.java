package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.HashingAlgorithm;
import ru.taska.domain.RefreshToken;
import ru.taska.domain.User;
import ru.taska.repository.RefreshTokenRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenService {


    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHashService passwordHashService;

    @Value("${jwt.refresh-token-ttl}")
    private long refreshTokenTtl;

    private static final SecureRandom secureRandom = new SecureRandom();

    public Mono<RefreshToken> createRefreshToken(User user) {
        return Mono.fromCallable(() -> {
                    String rawToken = generateRawToken();
                    String tokenHash = hashToken(rawToken);

                    RefreshToken refreshToken = RefreshToken.builder()
                            .id(UUID.randomUUID())
                            .userId(user.getId())
                            .tokenHash(tokenHash)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(refreshTokenTtl))
                            .createdAt(Instant.now())
                            .build();

                    return refreshToken;
                }).flatMap(refreshTokenRepository::save)
                .map(token -> {
                    // Возвращаем объект с raw token для ответа клиенту
                    token.setTokenHash(generateRawToken()); // Временно подменяем для ответа
                    return token;
                });
    }

    public Mono<RefreshToken> validateAndRotate(String rawToken) {
        String tokenHash = hashToken(rawToken);

        return refreshTokenRepository.findValidToken(tokenHash, Instant.now())
                .switchIfEmpty(Mono.error(new RuntimeException("Invalid or expired refresh token")))
                .flatMap(existingToken -> {
                    // Отзываем старый токен
                    UUID newTokenId = UUID.randomUUID();
                    return refreshTokenRepository.revokeToken(Instant.now(), newTokenId, existingToken.getId())
                            .then(createNewRefreshToken(existingToken.getUserId()))
                            .map(newToken -> {
                                newToken.setTokenHash(generateRawToken()); // Для ответа
                                return newToken;
                            });
                });
    }

    private Mono<RefreshToken> createNewRefreshToken(UUID userId) {
        return Mono.fromCallable(() -> {
            String rawToken = generateRawToken();
            String tokenHash = hashToken(rawToken);

            return RefreshToken.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .tokenHash(tokenHash)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(refreshTokenTtl))
                    .createdAt(Instant.now())
                    .build();
        }).flatMap(refreshTokenRepository::save);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        return passwordHashService.encode(rawToken, HashingAlgorithm.BCRYPT);
    }
}
