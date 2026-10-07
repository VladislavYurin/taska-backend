package ru.taska.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.config.JwtProperties;
import ru.taska.service.AccountLockService;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link RefreshTokenServiceImpl}.
 *
 * <p>Ключевое: проверка статуса и лока — ДО сохранения нового токена,
 * чтобы отказ не сжигал старый refresh.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenServiceImpl Unit Tests")
class RefreshTokenServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private AccountLockService accountLockService;

    private RefreshTokenServiceImpl service;

    private UUID userId;
    private User activeUser;
    private User blockedUser;
    private User invitedUser;
    private User lockedUser;
    private RefreshToken existingToken;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        Mockito.lenient().when(jwtProperties.getRefreshTokenTtl()).thenReturn(Duration.ofHours(1));

        activeUser = User.builder().id(userId).status(UserStatus.ACTIVE).build();
        blockedUser = User.builder().id(userId).status(UserStatus.BLOCKED).build();
        invitedUser = User.builder().id(userId).status(UserStatus.INVITED).build();
        lockedUser = User.builder().id(userId).status(UserStatus.LOCKED)
                .lockedUntil(NOW.plusSeconds(600)).build();

        existingToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash("old-hash")
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(3600))
                .build();

        service = new RefreshTokenServiceImpl(
                refreshTokenRepository, userRepository, jwtProperties, accountLockService);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("ACTIVE — выдаёт новую пару, отзывает старый")
        void active_rotates() {
            Mockito.when(refreshTokenRepository.findValidToken(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(existingToken));
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.just(activeUser));
            Mockito.when(accountLockService.resolveLockState(activeUser)).thenReturn(Mono.just(activeUser));
            Mockito.when(refreshTokenRepository.save(ArgumentMatchers.any(RefreshToken.class)))
                    .thenAnswer(inv -> {
                        RefreshToken t = inv.getArgument(0);
                        t.setId(UUID.randomUUID());
                        return Mono.just(t);
                    });
            Mockito.when(refreshTokenRepository.markReplacedIfActive(
                            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.validateAndRotate("raw"))
                    .assertNext(dto -> assertThat(dto.getRawToken()).isNotBlank())
                    .verifyComplete();

            Mockito.verify(refreshTokenRepository).save(ArgumentMatchers.any(RefreshToken.class));
            Mockito.verify(refreshTokenRepository).markReplacedIfActive(
                    Mockito.eq(existingToken.getId()), ArgumentMatchers.any(), ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Status checks — old token must NOT be burned on rejection")
    class StatusChecks {

        private void stubTokenAndUser(User user) {
            Mockito.when(refreshTokenRepository.findValidToken(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(existingToken));
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.just(user));
            Mockito.when(accountLockService.resolveLockState(user)).thenReturn(Mono.just(user));
        }

        private void assertUnauthenticatedAndOldTokenIntact() {
            Mockito.verify(refreshTokenRepository, Mockito.never()).save(ArgumentMatchers.any());
            Mockito.verify(refreshTokenRepository, Mockito.never()).markReplacedIfActive(
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("BLOCKED — UNAUTHENTICATED, старый refresh НЕ отозван")
        void blocked_rejectsWithoutBurning() {
            stubTokenAndUser(blockedUser);

            StepVerifier.create(service.validateAndRotate("raw"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de && de.getStatus() == DomainStatus.UNAUTHENTICATED)
                    .verify();

            assertUnauthenticatedAndOldTokenIntact();
        }

        @Test
        @DisplayName("INVITED — UNAUTHENTICATED, старый refresh НЕ отозван")
        void invited_rejectsWithoutBurning() {
            stubTokenAndUser(invitedUser);

            StepVerifier.create(service.validateAndRotate("raw"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de && de.getStatus() == DomainStatus.UNAUTHENTICATED)
                    .verify();

            assertUnauthenticatedAndOldTokenIntact();
        }

        @Test
        @DisplayName("LOCKED с активным окном — UNAUTHENTICATED, старый refresh НЕ отозван")
        void lockedActiveWindow_rejectsWithoutBurning() {
            stubTokenAndUser(lockedUser);

            StepVerifier.create(service.validateAndRotate("raw"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de && de.getStatus() == DomainStatus.UNAUTHENTICATED)
                    .verify();

            assertUnauthenticatedAndOldTokenIntact();
        }
    }

    @Nested
    @DisplayName("Error paths")
    class ErrorPaths {

        @Test
        @DisplayName("Токен не найден / истёк — UNAUTHENTICATED, БД по юзеру не дёргается")
        void tokenNotFound_shortCircuits() {
            Mockito.when(refreshTokenRepository.findValidToken(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.validateAndRotate("raw"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().contains("Invalid or expired refresh token"))
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findById(ArgumentMatchers.any(UUID.class));
            Mockito.verify(accountLockService, Mockito.never()).resolveLockState(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Юзер не найден — NOT_FOUND")
        void userNotFound_throwsNotFound() {
            Mockito.when(refreshTokenRepository.findValidToken(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(existingToken));
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.empty());

            StepVerifier.create(service.validateAndRotate("raw"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de && de.getStatus() == DomainStatus.NOT_FOUND)
                    .verify();

            Mockito.verify(accountLockService, Mockito.never()).resolveLockState(ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Lock resolution before status check")
    class LockResolution {

        @Test
        @DisplayName("LOCKED с истёкшим окном — resolveLockState вернул ACTIVE → ротация успешна")
        void lockedExpiredWindow_resolvesToActive_andRotates() {
            User lockedStale = User.builder().id(userId).status(UserStatus.LOCKED)
                    .lockedUntil(NOW.minusSeconds(60)).build();
            User refreshed = User.builder().id(userId).status(UserStatus.ACTIVE).build();

            Mockito.when(refreshTokenRepository.findValidToken(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(existingToken));
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.just(lockedStale));
            Mockito.when(accountLockService.resolveLockState(lockedStale)).thenReturn(Mono.just(refreshed));
            Mockito.when(refreshTokenRepository.save(ArgumentMatchers.any(RefreshToken.class)))
                    .thenAnswer(inv -> {
                        RefreshToken t = inv.getArgument(0);
                        t.setId(UUID.randomUUID());
                        return Mono.just(t);
                    });
            Mockito.when(refreshTokenRepository.markReplacedIfActive(
                            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.validateAndRotate("raw"))
                    .assertNext(dto -> assertThat(dto.getRawToken()).isNotBlank())
                    .verifyComplete();

            Mockito.verify(accountLockService).resolveLockState(lockedStale);
            Mockito.verify(refreshTokenRepository).markReplacedIfActive(
                    Mockito.eq(existingToken.getId()), ArgumentMatchers.any(), ArgumentMatchers.any());
        }
    }
}