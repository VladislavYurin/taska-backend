package ru.taska.dto;

import lombok.Builder;

/**
 * Счётчики проблемных outbox-событий для одного сервиса.
 *
 * @param serviceKey          ключ сервиса (например, {@code "auth"}, {@code "project"}, {@code "issue"})
 * @param overdueNewCount     количество событий в статусе {@code NEW}, не взятых в обработку
 *                            дольше допустимого порога
 * @param stuckProcessingCount количество событий, зависших в статусе {@code PROCESSING}
 *                            дольше допустимого таймаута
 * @param failedCount         количество событий в статусе {@code FAILED}
 */
@Builder
public record ProblematicEventCountDto(
        String serviceKey,
        long overdueNewCount,
        long stuckProcessingCount,
        long failedCount
) {
}
