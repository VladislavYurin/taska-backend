package ru.taska.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.JwtService;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtValidator Reactive Tests")
class JwtValidatorTest {

    @Mock
    private JwtService jwtService;

    private static final String SECRET_KEY_STRING = "my-secret-key-for-testing-jwt-validation-1234567890";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_KEY_STRING.getBytes(StandardCharsets.UTF_8));
    private static final String USER_ID = UUID.randomUUID().toString();

    private JwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator(SECRET_KEY, jwtService);
    }

    @Test
    @DisplayName("Should validate valid JWT token successfully")
    void shouldValidateValidJwtToken() {
        // Given
        String token = generateValidToken(USER_ID);

        // When & Then
        StepVerifier.create(jwtValidator.validate(token))
                .assertNext(claims -> {
                    assertThat(claims).isNotNull();
                    assertThat(claims.getSubject()).isEqualTo(USER_ID);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT for null token")
    void shouldReturnInvalidArgumentForNullToken() {
        // Given
        String token = null;

        // When & Then
        StepVerifier.create(jwtValidator.validate(token))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                error.getMessage().equals("Token required")
                )
                .verify();
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT for blank token")
    void shouldReturnInvalidArgumentForBlankToken() {
        // Given
        String token = "";

        // When & Then
        StepVerifier.create(jwtValidator.validate(token))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                error.getMessage().equals("Token required")
                )
                .verify();
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT for whitespace token")
    void shouldReturnInvalidArgumentForWhitespaceToken() {
        // Given
        String token = "   ";

        // When & Then
        StepVerifier.create(jwtValidator.validate(token))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                error.getMessage().equals("Token required")
                )
                .verify();
    }

    @Test
    @DisplayName("Should return UNAUTHENTICATED for expired JWT token")
    void shouldReturnUnauthenticatedForExpiredToken() {
        // Given
        String expiredToken = generateExpiredToken(USER_ID);

        // When & Then
        StepVerifier.create(jwtValidator.validate(expiredToken))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                error.getMessage().equals("JWT token expired")
                )
                .verify();
    }

    @Test
    @DisplayName("Should return UNAUTHENTICATED for invalid JWT token")
    void shouldReturnUnauthenticatedForInvalidToken() {
        // Given
        String invalidToken = "invalid.token.signature";

        // When & Then
        StepVerifier.create(jwtValidator.validate(invalidToken))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                error.getMessage().equals("Invalid JWT token")
                )
                .verify();
    }

    @Test
    @DisplayName("Should return UNAUTHENTICATED for JWT token with wrong signature")
    void shouldReturnUnauthenticatedForTokenWithWrongSignature() {
        // Given
        String wrongSignatureToken = generateTokenWithWrongSignature(USER_ID);

        // When & Then
        StepVerifier.create(jwtValidator.validate(wrongSignatureToken))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                error.getMessage().equals("Invalid JWT token")
                )
                .verify();
    }

    @Test
    @DisplayName("Should extract all claims from valid token")
    void shouldExtractAllClaimsFromValidToken() {
        // Given
        String token = generateTokenWithCustomClaims(USER_ID);

        // When & Then
        StepVerifier.create(jwtValidator.validate(token))
                .assertNext(claims -> {
                    assertThat(claims).isNotNull();
                    assertThat(claims.getSubject()).isEqualTo(USER_ID);
                    assertThat(claims.get("role")).isEqualTo("ADMIN");
                    assertThat(claims.get("tenantId")).isEqualTo("tenant-123");
                    assertThat(claims.getIssuer()).isEqualTo("test-issuer");
                })
                .verifyComplete();
    }

    // ==================== HELPER METHODS ====================

    private String generateValidToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofHours(1).toMillis());

        return Jwts.builder()
                .subject(userId)
                .issuer("test-issuer")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(SECRET_KEY)
                .compact();
    }

    private String generateExpiredToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() - Duration.ofHours(1).toMillis());

        return Jwts.builder()
                .subject(userId)
                .issuer("test-issuer")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(SECRET_KEY)
                .compact();
    }

    private String generateTokenWithWrongSignature(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofHours(1).toMillis());

        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "different-secret-key-for-testing-1234567890".getBytes(StandardCharsets.UTF_8)
        );

        return Jwts.builder()
                .subject(userId)
                .issuer("test-issuer")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(wrongKey)
                .compact();
    }

    private String generateTokenWithCustomClaims(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofHours(1).toMillis());

        return Jwts.builder()
                .subject(userId)
                .issuer("test-issuer")
                .issuedAt(now)
                .expiration(expiry)
                .claim("role", "ADMIN")
                .claim("tenantId", "tenant-123")
                .signWith(SECRET_KEY)
                .compact();
    }
}