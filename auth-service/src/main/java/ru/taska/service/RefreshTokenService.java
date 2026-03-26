package ru.taska.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public Mono<String> createRefreshToken(User user) {
        return Mono.fromCallable(() -> {
                    String rawToken = generateRawToken();
                    String tokenHash = hashToken(rawToken);

                    RefreshToken refreshToken = RefreshToken.builder()
                            //.id(UUID.randomUUID())
                            .userId(user.getId())
//  -->>                    .tokenHash(rawToken)
                            .tokenHash(tokenHash)
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(refreshTokenTtl))
                            .createdAt(Instant.now())
                            .build();

                    System.out.println(">>> createRefreshToken: saving token with id=" + refreshToken.getId() +
                            ", hash=" + tokenHash);

                    return new TokenPair(refreshToken, rawToken);
                })
                .flatMap(tokenPair -> refreshTokenRepository.save(tokenPair.refreshToken)
                        .doOnSuccess(saved -> System.out.println(">>> createRefreshToken: saved token with id=" + saved.getId()))
                        .thenReturn(tokenPair.rawToken));
    }

    @Transactional
    public Mono<RefreshTokenResponse> validateAndRotate(String rawToken) {
        String tokenHash = hashToken(rawToken);  // <---

        log.debug(">>> validateAndRotate: recieved rawToken =" + rawToken);
        log.debug(">>> validateAndRotate: looking for token_hash in DB=" + tokenHash);  // <--

        return refreshTokenRepository.findValidToken(tokenHash, Instant.now())   //<---
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug(">>> validateAndRotate: token not found in DB");
                    return Mono.error(new RuntimeException("Invalid or expired refresh token"));
                }))
                .flatMap(existingToken -> {
                    log.debug(">>> validateAndRotate: found existing token with id=" + existingToken.getId());

                    // Генерируем новый токен

                    String newRawToken = generateRawToken();
                    String newTokenHash = hashToken(newRawToken);  // <---
                    UUID newTokenId = UUID.randomUUID();

                    RefreshToken newRefreshToken = RefreshToken.builder()
                            .id(newTokenId)
                            .userId(existingToken.getUserId())
//                            .tokenHash(newRawToken) ///<-----
                            .tokenHash(newTokenHash) ///<-----
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(refreshTokenTtl))
                            .createdAt(Instant.now())
                            .build();

                    log.debug(">>> validateAndRotate: creating new token with id=" + newTokenId +
                            ", newRawToken=" + newRawToken);

                    return refreshTokenRepository.save(newRefreshToken)
                            .map(savedNewToken -> {
                                log.debug(">>> validateAndRotate: saved new token with id={}", savedNewToken.getId());
                                return  new RefreshTokenResponse(savedNewToken, newRawToken);
                            });



//                    return refreshTokenRepository.save(newRefreshToken)
//                            .flatMap(savedNewToken -> {
//                                log.debug(">>> validateAndRotate: saved new token with id={}", savedNewToken.getId());
//
//                                // Затем отзываем старый токен, указывая replaced_by на ID нового токена
//                                return refreshTokenRepository.revokeToken(Instant.now(), savedNewToken.getId(), existingToken.getId())
//                                        .doOnSuccess(updated -> log.debug(">>> validateAndRotate: revoked old token, rows updated={}", updated))
//                                        .thenReturn(new RefreshTokenResponse(savedNewToken, newRawToken));
//                            });
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

    @Getter
    @AllArgsConstructor
    public static class RefreshTokenResponse {
        private final RefreshToken refreshToken;
        private final String rawToken;
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