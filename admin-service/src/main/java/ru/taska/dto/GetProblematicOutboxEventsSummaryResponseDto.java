package ru.taska.dto;

import java.util.List;

/**
 * Сводный ответ по проблемным outbox-событиям.
 *
 * @param events      список проблемных событий (ограничен настройкой {@code maxProblematicListSize})
 * @param counts      счётчики проблемных событий по каждому сервису (всегда по всем сервисам,
 *                    независимо от фильтрации в {@code events})
 * @param notAllShown {@code true}, если найдено больше проблемных событий, чем вмещает лимит,
 *                    и часть событий не вошла в {@code events}
 */
public record GetProblematicOutboxEventsSummaryResponseDto(
        List<ProblematicOutboxEventResponseDto> events,
        List<ProblematicEventCountDto> counts,
        boolean notAllShown
) {
}
