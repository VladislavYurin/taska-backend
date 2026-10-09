package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.UserRepository;

import java.time.Instant;

/**
 * Управляет состоянием LOCKED аккаунта.
 *
 * <p>Единственный источник правды о локе — {@code users.locked_until}.
 * {@link UserStatus#LOCKED} — производное состояние, снимается лениво на
 * write-путях: {@code login} и {@code refresh}.
 * Read-путь ({@code validateAccessToken}) лок <b>не</b> снимает.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountLockService {

    private final UserRepository userRepository;

    /**
     * Снимает LOCKED, если окно lockedUntil истекло.
     *
     * <p>Возвращает актуального пользователя:</p>
     * <ul>
     *   <li>{@code ACTIVE} — если лок был снят (или его не было);</li>
     *   <li>{@code LOCKED} — если окно ещё активно.</li>
     * </ul>
     * <p><b>Вызывается только из транзакционных write-путей</b> ({@code login},
     * {@code refresh}): сервис не открывает транзакцию сам, атомарность
     * {@code UPDATE} + {@code SELECT} обеспечивается внешней транзакцией.</p>
     * <p>Не бросает исключений по статусу — решение «пускать или нет» принимает вызывающий.</p>
     *
     * @param user пользователь (может быть устаревшим снимком)
     * @return актуальный пользователь
     */
    public Mono<User> resolveLockState(User user) {
        if (user.getStatus() != UserStatus.LOCKED) {
            return Mono.just(user);
        }
        Instant until = user.getLockedUntil();
        if (until != null && until.isAfter(Instant.now())) {
            // окно ещё активно — не трогаем
            return Mono.just(user);
        }
        return userRepository.unlockIfExpired(user.getId())
                // UPDATE не сработал: кто-то уже разлочил, либо условие перестало совпадать —
                // перечитываем актуальное состояние
                .switchIfEmpty(Mono.defer(() -> userRepository.findById(user.getId())))
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")));
    }
}