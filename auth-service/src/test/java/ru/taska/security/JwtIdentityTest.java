package ru.taska.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import ru.taska.entity.User;
import ru.taska.security.config.JwtProperties;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtIdentityTest {
    @Test
    void tokenUsesUuidAndEmailWithoutUsernameClaim() {
        var key = Jwts.SIG.HS256.key().build();
        var properties = new JwtProperties();
        properties.setAccessTokenTtl(Duration.ofMinutes(5));
        var service = new JwtServiceImpl(properties, key);
        UUID id = UUID.randomUUID();
        var user = User.builder().id(id).email("person@example.com").displayName("Person").build();

        String token = service.generateAccessToken(user).block();
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.get("userId", String.class)).isEqualTo(id.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("person@example.com");
        assertThat(claims).doesNotContainKey("login");
    }
}
