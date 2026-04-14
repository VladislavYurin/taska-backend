package ru.taska.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JwtValidator {

    private final SecretKey secretKey;

    // конструктор подтягивает секрет из yaml файла
    // в будущем будет подтягивать из env или из vault
    public JwtValidator(@Value("${jwt.secret}") String secretString) {
        // создаём объект secretKey в конструкторе
        byte[] decodedString = secretString.getBytes();
        this.secretKey = Keys.hmacShaKeyFor(decodedString);
    }


    // метод валидации токена
    public Claims validateToken(String token) {
        log.debug("[jwt-validator]: валидация токена...");
        return Jwts.parser()
                   // decryptWith(secretKey) - если зашифрован
                   .verifyWith(secretKey)
                   .build()
                   .parseSignedClaims(token).getPayload();
                    // ok, we can trust this jwt
    }
}
