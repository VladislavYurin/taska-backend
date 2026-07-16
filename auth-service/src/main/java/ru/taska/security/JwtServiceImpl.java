package ru.taska.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.apache.commons.codec.digest.DigestUtils;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.entity.User;
import ru.taska.security.config.JwtProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Реализация сервиса для генерации JWT-токенов доступа.
 * <p>Использует конфигурацию из {@link JwtProperties} для настройки секрета и времени жизни токена.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    @Override
    public Mono<String> generateAccessToken(User user) {
        return Mono.fromCallable(() ->
                Jwts.builder()
                        .claims()
                        .add("userId", user.getId().toString())
                        .add("login", user.getLogin())
                        .add("email", user.getEmail())
                        .add("global_role",user.getGlobalRole())
                        .subject(user.getId().toString())    // вместо setSubject()
                        .issuedAt(new Date())                // вместо setIssuedAt()
                        .expiration(new Date(               // вместо setExpiration()
                                System.currentTimeMillis() +
                                        jwtProperties.getAccessTokenTtl().getSeconds() * 1000
                        ))
                        .and()                              // возврат к builder
                        .signWith(secretKey)
                        .compact()
        ).onErrorMap(e ->
                new DomainException(DomainStatus.INTERNAL, "Failed to generate access token")
        );
    }

    public Mono<Long> getExpiresIn() {
        return Mono.just(jwtProperties.getAccessTokenTtl().getSeconds());
    }

    @Override
    public String hashToken(String rawToken) {
        return DigestUtils.sha256Hex(rawToken);
    }
}