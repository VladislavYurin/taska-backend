package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import ru.taska.domain.ProcessedEvent;

/**
 * Репозиторий для хранения обработанных событий Kafka.
 *
 * <p>Используется для дедупликации по {@code eventId}, чтобы повторная
 * доставка одного и того же события не приводила к созданию дублей
 * уведомлений.</p>
 */
public interface ProcessedEventRepository extends ReactiveCrudRepository<ProcessedEvent, String> {
}

