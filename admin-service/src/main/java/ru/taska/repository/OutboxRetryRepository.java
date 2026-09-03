package ru.taska.repository;

import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEventSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * Репозиторий ограниченных административных операций
 * над transactional outbox сервисов.
 * <p>
 * Не предоставляет generic CRUD. Репозиторий предназначен
 * исключительно для чтения состояния outbox-события
 * и его перевода обратно в состояние NEW при ручном retry.
 */
public interface OutboxRetryRepository {

    /**
     * Получает текущее состояние outbox-события.
     *
     * @param service сервис-владелец outbox
     * @param eventId идентификатор outbox-события
     * @return snapshot события или empty, если событие не найдено
     */
    Mono<OutboxEventSnapshot> findById(
            String service,
            UUID eventId
    );

    /**
     * Пытается перевести событие в состояние NEW.
     * <p>
     * Изменение выполняется только если событие находится
     * в статусе FAILED либо в зависшем PROCESSING.
     * Количество предыдущих попыток обработки сохраняется.
     *
     * @param service     сервис-владелец outbox
     * @param eventId     идентификатор события
     * @param stuckBefore момент времени, раньше которого PROCESSING
     *                    считается зависшим
     * @return количество изменённых строк
     */
    Mono<Long> retry(
            String service,
            UUID eventId,
            Instant stuckBefore
    );
}