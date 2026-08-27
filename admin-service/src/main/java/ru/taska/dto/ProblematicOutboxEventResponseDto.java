package ru.taska.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * Детальная информация о проблемном outbox-событии.
 *
 * @param id                  идентификатор события в таблице {@code outbox_events}
 * @param aggregateType       тип агрегата, к которому относится событие (например, {@code "Task"})
 * @param aggregateId         идентификатор агрегата
 * @param eventType           тип события (например, {@code "TaskCreated"})
 * @param payload             тело события (может быть замаскировано при наличии чувствительных данных)
 * @param status              текущий статус события ({@code NEW}, {@code PROCESSING}, {@code FAILED})
 * @param createdAt           момент создания события
 * @param publishedAt         момент публикации события в Kafka ({@code null}, если ещё не опубликовано)
 * @param attempts            количество попыток обработки
 * @param lastErrorMessage    сообщение последней ошибки ({@code null}, если ошибок не было)
 * @param processingStartedAt момент начала текущей обработки ({@code null}, если не в PROCESSING)
 * @param requestId           идентификатор исходного запроса, породившего событие
 * @param serviceKey          ключ сервиса-источника (например, {@code "auth"}, {@code "issue"})
 * @param reason              человекочитаемая причина, по которой событие считается проблемным
 */
@Builder
public record ProblematicOutboxEventResponseDto(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String status,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        String lastErrorMessage,
        Instant processingStartedAt,
        String requestId,
        String serviceKey,
        String reason
) {
}
