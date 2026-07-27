package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AuditEventDto;

/**
 * Сервис аутентичности и учета административных действий (аудита).
 * <p>Предоставляет методы для фиксации и сохранения событий аудита в системе.</p>
 */
public interface AuditService {

    /**
     * Валидирует и сохраняет событие аудита.
     *
     * @param dto DTO события аудита, содержащее контекст выполнения действия
     * @return {@link Mono}, завершающийся успешно после сохранения записи аудита,
     *         либо завершающийся ошибкой {@link ru.taska.exception.DomainException},
     *         если не заполнены обязательные поля (reason)
     */
    Mono<Void> logAudit(AuditEventDto dto);

}
