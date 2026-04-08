package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.JwtProperties;
import ru.taska.domain.HashingAlgorithm;
import ru.taska.domain.RefreshToken;
import ru.taska.domain.User;
import ru.taska.dto.RefreshTokenResponseDto;
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
    private final JwtProperties jwtProperties;

    private static final SecureRandom secureRandom = new SecureRandom();

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

                    log.debug(">>> createRefreshToken: saving token with id=" + refreshToken.getId() +
                            ", hash=" + tokenHash);

                    return new TokenPair(refreshToken, rawToken);
                })
                .flatMap(tokenPair -> refreshTokenRepository.save(tokenPair.refreshToken)
                        .doOnSuccess(saved -> {
                            assert saved != null;
                            log.debug(">>> createRefreshToken: saved token with id=" + saved.getId());
                        })
                        .thenReturn(tokenPair.rawToken));
    }

    @Transactional
    public Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken) {
        String tokenHash = hashToken(rawToken);

        log.debug(">>> validateAndRotate: recieved rawToken =" + rawToken);
        log.debug(">>> validateAndRotate: looking for token_hash in DB=" + tokenHash);  // <--

        return refreshTokenRepository.findValidToken(tokenHash, Instant.now())   //<---
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug(">>> validateAndRotate: token not found in DB");
                    return Mono.error(new RuntimeException("Invalid or expired refresh token"));
                }))
                .flatMap(existingToken -> {
                    System.out.println(">>> validateAndRotate: found existing token with id=" + existingToken.getId());

                    String newRawToken = generateRawToken();
                    String newTokenHash = hashToken(newRawToken);
                    UUID newTokenId = UUID.randomUUID();

                    RefreshToken newRefreshToken = RefreshToken.builder()
                            .userId(existingToken.getUserId())
                            .tokenHash(newTokenHash)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenTtl()))
                            .createdAt(Instant.now())
                            .build();

                    log.debug(">>> validateAndRotate: creating new token with id=" + newTokenId +
                            ", newRawToken=" + newRawToken);

                    return refreshTokenRepository.save(newRefreshToken)
                            .flatMap(savedNewToken -> {
                                log.debug(">>> validateAndRotate: saved new token with id=" + savedNewToken.getId());

                                // Отзываем старый токен
                                return refreshTokenRepository.revokeToken(Instant.now(), existingToken.getId())
                                        .doOnSuccess(updated -> {
                                            log.debug(">>> validateAndRotate: revoked old token, rows updated=" + updated);
                                        })
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