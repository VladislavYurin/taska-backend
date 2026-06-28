package ru.taska.util;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.repository.IdempotencyKeyRepository;

/**
 * Планировщик очистки протухших ключей идемпотентности.
 *
 * <p>Периодически удаляет из таблицы {@code taska.idempotency_keys} записи с истёкшим
 * сроком действия ({@code expires_at}), чтобы таблица не росла бесконечно. </p>
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeyScheduler {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Удаляет протухшие ключи идемпотентности.
     *
     * <p>Запускается по расписанию с интервалом {@code issue.idempotency-key-ttl.clean-interval}.</p>
     */
    @Scheduled(fixedDelayString = "${issue.idempotency-key-ttl.clean-interval}")
    public void deleteExpiredIdempotencyKeys() {
        idempotencyKeyRepository.deleteByExpiresAtBefore()
                                .doOnNext(deleted -> log.debug("Deleted expires idempotency keys: {}", deleted))
                                .doOnError(e -> log.error("Idempotency cleanup failed: {}", e.getMessage()))
                                .subscribe();
    }
}
