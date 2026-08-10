package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssueTypeDto;

import java.util.UUID;

/**
 * Интерфейс сервиса для агрегации данных доски задач на стороне API Gateway.
 */
public interface BoardService {

    /**
     * Формирует агрегированное представление доски задач (Board) для проекта.
     *
     * @param projectId   идентификатор проекта
     * @param issueType   тип задач для отображения на доске
     * @param assigneeId  опциональный идентификатор исполнителя для фильтрации
     * @param labelId     опциональный идентификатор метки для фильтрации
     * @param includeDone флаг, определяющий, нужно ли включать выполненные задачи
     * @param context     сквозной контекст выполнения запроса на Gateway
     * @return {@link Mono} с DTO ответа {@link BoardResponseDto}, содержащим колонки и задачи
     */
    Mono<BoardResponseDto> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            GatewayContext context
    );
}