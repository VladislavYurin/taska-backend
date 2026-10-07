package ru.taska.dto;


import lombok.Builder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO для представления записи аудита в ответе API.
 * Содержит информацию о действии пользователя, измененных данных и контексте операции.
 *
 * @param actorUserId   идентификатор пользователя, выполнившего действие (UUID)
 * @param actorLogin    логин пользователя, выполнившего действие
 * @param action        тип действия (например: CREATE, UPDATE, DELETE)
 * @param targetService название сервиса, в котором произошло изменение
 * @param targetTable   название таблицы, в которой произошло изменение
 * @param targetId      идентификатор записи, над которой выполнено действие
 * @param oldValue      Состояние объекта до изменения.
 * @param newValue      Состояние объекта после изменения.
 * @param reason        Причина выполнения административного действия.
 * @param requestId     Идентификатор запроса для трассировки цепочки вызовов.
 * @param createdAt     Временная метка создания записи.
 */
@Builder
public record AuditEntriesResponseDto(
        UUID actorUserId,
        String actorLogin,
        String action,
        String targetService,
        String targetTable,
        String targetId,
        JsonNode oldValue,
        JsonNode newValue,
        String reason,
        String requestId,
        Instant createdAt
) {
}


