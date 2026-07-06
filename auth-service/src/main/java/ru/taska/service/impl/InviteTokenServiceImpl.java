package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.InviteTokenResponse;
import ru.taska.entity.InviteToken;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.security.config.JwtProperties;
import ru.taska.service.InviteTokenService;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InviteTokenServiceImpl implements InviteTokenService {

    private final InviteTokenRepository inviteTokenRepository;
    private final JwtProperties jwtProperties;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Создаёт одноразовый invite токен в БД (хранит хеш)
     * @return DTO с незахешироанным токеном.
     */
    @Override
    public Mono<InviteTokenResponse> createInviteToken(UUID userId) {
        String rawToken = generateRawToken();
        InviteToken tokenEntity = InviteToken.builder()
                .userId(userId)
                .tokenHash(hashToken(rawToken))
                .expiresAt(Instant.now().plus(jwtProperties.getInviteTokenTtl().toDays(), ChronoUnit.DAYS))
                .build();
        return inviteTokenRepository.save(tokenEntity)
                .map(savedToken -> InviteTokenResponse.builder()
                        .id(savedToken.getId())
                        .userId(savedToken.getUserId())
                        .rawToken(rawToken)
                        .expiresAt(savedToken.getExpiresAt())
                        .build());
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        return DigestUtils.sha256Hex(rawToken);
    }
}
