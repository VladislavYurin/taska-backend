package ru.taska.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO для фильтрации записей аудита.
 * Используется как параметры поиска/фильтрации при запросе списка аудита.
 * Все поля опциональны - null означает отсутствие фильтра по данному критерию.
 *
 * @param actorUserId   фильтр по идентификатору пользователя, выполнившего действие
 * @param action        фильтр по типу действия (CREATE, UPDATE, DELETE)
 * @param targetService фильтр по названию сервиса, в котором произошло изменение
 * @param targetTable   фильтр по названию таблицы, в которой произошло изменение
 * @param targetId      фильтр по идентификатору записи, над которой выполнено действие
 * @param requestId     фильтр по идентификатору HTTP-запроса
 * @param createdAtFrom фильтр по дате и времени: начало периода (включительно)
 * @param createdAtTo   фильтр по дате и времени: конец периода (включительно)
 */
@Builder
public record FilterAuditDTO(UUID actorUserId,
                             String action,
                             String targetService,
                             String targetTable,
                             String targetId,
                             String requestId,
                             Instant createdAtFrom,
                             Instant createdAtTo) {
}
