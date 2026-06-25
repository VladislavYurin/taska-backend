package ru.taska.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.JwtService;

import javax.crypto.SecretKey;

@Slf4j
@RequiredArgsConstructor
@Component
public class JwtValidator {

    private final SecretKey secretKey;
    private final JwtService jwtService;

    public Mono<Claims> validate(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Token required"));
        }

        String maskedToken = DataMaskingHelper.maskJwt(jwtService.hashToken(jwt));

        return Mono.fromCallable(() -> {
            log.debug("Validating JWT token: {}", maskedToken);
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        }).onErrorResume(ExpiredJwtException.class, e -> {
            log.warn("JWT token expired: {}", maskedToken);
            return Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "JWT token expired"));
        }).onErrorResume(JwtException.class, e -> {
            log.warn("Invalid JWT token: {}", maskedToken);
            return Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid JWT token"));
        });
    }
}
