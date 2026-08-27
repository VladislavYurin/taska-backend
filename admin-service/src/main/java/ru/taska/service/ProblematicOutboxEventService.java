package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;

/**
 * Сервис мониторинга проблемных событий в Outbox-таблицах микросервисов.
 *
 * <p>Admin-service подключается к БД каждого сервиса (auth, project, issue) через
 * read-only соединения и анализирует их таблицы {@code outbox_events}, выявляя события,
 * которые не были успешно обработаны: зависшие в статусе {@code PROCESSING} дольше таймаута,
 * не взятые в обработку из статуса {@code NEW} дольше порога, а также явно упавшие ({@code FAILED}).
 */
public interface ProblematicOutboxEventService {

    /**
     * Возвращает сводку по проблемным Outbox-событиям.
     *
     * <p>Ответ содержит:
     * <ul>
     *   <li>Счётчики проблемных событий по каждому сервису (overdue, stuck, failed);</li>
     *   <li>Список самих событий (с ограничением по {@code maxProblematicListSize});</li>
     *   <li>Флаг {@code notAllShown}, если событий больше, чем вмещает лимит.</li>
     * </ul>
     *
     * @param serviceKey ключ конкретного сервиса (например, {@code "auth"}) для фильтрации,
     *                   или {@code null} / пустая строка — для запроса по всем сервисам
     * @param requestId  идентификатор запроса (для логирования)
     * @param nodeId     идентификатор узла (для логирования)
     */
    Mono<GetProblematicOutboxEventsSummaryResponseDto> getProblematicOutboxEventsSummary(
            String serviceKey, String requestId, String nodeId);
}
