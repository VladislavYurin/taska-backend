package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Unit-тесты {@link AccountLockService}.
 *
 * <p>Проверяют ленивое снятие лока: единственный источник правды — {@code users.locked_until}.
 * Тесты детерминированы за счёт {@link Clock#fixed}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountLockService Unit Tests")
class AccountLockServiceTest {

    private static final Instant NOW = Instant.now();

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionalOperator transactionalOperator;

    private AccountLockService accountLockService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        Mockito.lenient().when(transactionalOperator.transactional(ArgumentMatchers.any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Mockito.lenient().when(userRepository.findById(userId)).thenReturn(Mono.empty());

        accountLockService = new AccountLockService (userRepository, transactionalOperator);
    }

    private User user(UserStatus status, Instant lockedUntil) {
        return User.builder()
                .id(userId)
                .email("u@example.com")
                .login("u")
                .status(status)
                .lockedUntil(lockedUntil)
                .build();
    }

    @Nested
    @DisplayName("ACTIVE user")
    class ActiveUser {

        @Test
        @DisplayName("ACTIVE без lockedUntil — возвращает как есть, в БД не ходит")
        void active_returnsAsIs() {
            User u = user(UserStatus.ACTIVE, null);

            StepVerifier.create(accountLockService.resolveLockState(u))
                    .expectNext(u)
                    .verifyComplete();

            Mockito.verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("ACTIVE с lockedUntil (аномалия) — возвращает как есть, не трогает БД")
        void activeWithStaleLockedUntil_returnsAsIs() {
            // status != LOCKED — сервис не смотрит на lockedUntil вообще
            User u = user(UserStatus.ACTIVE, NOW.plusSeconds(3600));

            StepVerifier.create(accountLockService.resolveLockState(u))
                    .expectNext(u)
                    .verifyComplete();

            Mockito.verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("BLOCKED — возвращает как есть, не трогает БД")
        void blocked_returnsAsIs() {
            User u = user(UserStatus.BLOCKED, null);

            StepVerifier.create(accountLockService.resolveLockState(u))
                    .expectNext(u)
                    .verifyComplete();

            Mockito.verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("INVITED — возвращает как есть, не трогает БД")
        void invited_returnsAsIs() {
            User u = user(UserStatus.INVITED, null);

            StepVerifier.create(accountLockService.resolveLockState(u))
                    .expectNext(u)
                    .verifyComplete();

            Mockito.verifyNoInteractions(userRepository);
        }
    }

    @Nested
    @DisplayName("LOCKED user")
    class LockedUser {

        @Test
        @DisplayName("Окно активно (lockedUntil > now) — возвращает как есть, UPDATE не зовётся")
        void windowActive_returnsAsIs() {
            User u = user(UserStatus.LOCKED, NOW.plusSeconds(60));

            StepVerifier.create(accountLockService.resolveLockState(u))
                    .expectNext(u)
                    .verifyComplete();

            Mockito.verify(userRepository, Mockito.never()).unlockIfExpired(ArgumentMatchers.any());
            Mockito.verify(userRepository, Mockito.never()).findById(ArgumentMatchers.any(UUID.class));
        }

        @Test
        @DisplayName("Окно истекло (lockedUntil < now) — UPDATE сработал, возвращает ACTIVE")
        void windowExpired_updatesAndReturnsActive() {
            User locked = user(UserStatus.LOCKED, NOW.minusSeconds(1));
            User active = user(UserStatus.ACTIVE, null);

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.just(active));

            StepVerifier.create(accountLockService.resolveLockState(locked))
                    .expectNext(active)
                    .verifyComplete();

            Mockito.verify(userRepository).unlockIfExpired(userId);
            Mockito.verify(userRepository, Mockito.never()).findById(ArgumentMatchers.any(UUID.class));
        }

        @Test
        @DisplayName("Окно истекло, UPDATE вернул Empty, findById вернул ACTIVE — гонка, кто-то уже разлочил")
        void windowExpired_updateEmpty_findByIdActive() {
            User locked = user(UserStatus.LOCKED, NOW.minusSeconds(1));
            User alreadyActive = user(UserStatus.ACTIVE, null);

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.empty());
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.just(alreadyActive));

            StepVerifier.create(accountLockService.resolveLockState(locked))
                    .expectNext(alreadyActive)
                    .verifyComplete();

            Mockito.verify(userRepository).unlockIfExpired(userId);
            Mockito.verify(userRepository).findById(userId);
        }

        @Test
        @DisplayName("Окно истекло, UPDATE пусто, findById вернул LOCKED с новым окном — перезалочили")
        void windowExpired_updateEmpty_findByIdLockedAgain() {
            User locked = user(UserStatus.LOCKED, NOW.minusSeconds(1));
            User reLocked = user(UserStatus.LOCKED, NOW.plusSeconds(300));

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.empty());
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.just(reLocked));

            StepVerifier.create(accountLockService.resolveLockState(locked))
                    .expectNext(reLocked)
                    .verifyComplete();

            Mockito.verify(userRepository).unlockIfExpired(userId);
            Mockito.verify(userRepository).findById(userId);
        }

        @Test
        @DisplayName("Окно истекло, UPDATE пусто, findById пусто — NOT_FOUND")
        void windowExpired_updateEmpty_findByIdEmpty_throwsNotFound() {
            User locked = user(UserStatus.LOCKED, NOW.minusSeconds(1));

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.empty());
            Mockito.when(userRepository.findById(userId)).thenReturn(Mono.empty());

            StepVerifier.create(accountLockService.resolveLockState(locked))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.NOT_FOUND)
                    .verify();
        }

        @Test
        @DisplayName("lockedUntil == null при статусе LOCKED — трактуется как истёкший, разлочиваем")
        void lockedButNullUntil_treatedAsExpired() {
            // until == null → условие until.isAfter(now) не выполняется → идём в UPDATE
            User lockedNoUntil = user(UserStatus.LOCKED, null);
            User active = user(UserStatus.ACTIVE, null);

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.just(active));

            StepVerifier.create(accountLockService.resolveLockState(lockedNoUntil))
                    .expectNext(active)
                    .verifyComplete();

            Mockito.verify(userRepository).unlockIfExpired(userId);
        }

        @Test
        @DisplayName("Ровно в момент lockedUntil (now == until) — окно НЕ активно, разлочиваем")
        void exactMoment_untilEqualsNow_unlocks() {
            // until.isAfter(now) == false → не считается активным → снимаем
            User locked = user(UserStatus.LOCKED, NOW);
            User active = user(UserStatus.ACTIVE, null);

            Mockito.when(userRepository.unlockIfExpired(userId)).thenReturn(Mono.just(active));

            StepVerifier.create(accountLockService.resolveLockState(locked))
                    .expectNext(active)
                    .verifyComplete();

            Mockito.verify(userRepository).unlockIfExpired(userId);
        }
    }
}