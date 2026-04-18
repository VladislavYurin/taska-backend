package ru.taska.security;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.HashingAlgorithm;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.security.config.JwtProperties;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHashService passwordHashService;
    private final JwtProperties jwtProperties;

    private static final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public Mono<String> createRefreshToken(User user) {
        return Mono.fromCallable(() -> {
                    String rawToken = generateRawToken();
                    String tokenHash = hashToken(rawToken);

                    RefreshToken refreshToken = RefreshToken.builder()
                            .userId(user.getId())
                            .tokenHash(tokenHash)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenTtl()))
                            .createdAt(Instant.now())
                            .build();

                    log.debug("Creating refresh token for user: {}", user.getId());

                    return new TokenPair(refreshToken, rawToken);
                })
                .flatMap(tokenPair -> refreshTokenRepository.save(tokenPair.refreshToken)
                        .thenReturn(tokenPair.rawToken));
    }

    @Override
    @Transactional
    public Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken) {
        String tokenHash = hashToken(rawToken);

        log.debug("Validating and rotating refresh token");

        return refreshTokenRepository.findValidToken(tokenHash, Instant.now())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED,
                        "Invalid or expired refresh token")))
                .flatMap(existingToken -> {
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

                                // Revoke old token
                                return refreshTokenRepository.revokeToken(Instant.now(), existingToken.getId())
                                        .doOnSuccess(updated -> log.debug("Revoked old token, rows updated: {}", updated))
                                        .thenReturn(new RefreshTokenResponseDto(savedNewToken, newRawToken));
                            });
                });
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        return passwordHashService.encode(rawToken, HashingAlgorithm.BCRYPT);
    }

    private static class TokenPair {
        final RefreshToken refreshToken;
        final String rawToken;

        TokenPair(RefreshToken refreshToken, String rawToken) {
            this.refreshToken = refreshToken;
            this.rawToken = rawToken;
        }
    }
}