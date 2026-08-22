package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.BoardApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.service.BoardService;
import java.util.UUID;

/**
 * REST-контроллер API Gateway для предоставления данных о доске задач (Board).
 * <p>
 * Обеспечивает проксирование и агрегацию данных из микросервисов workflow-service
 * и issue-service для построения структуры доски (колонки и задачи).
 * Реализует сгенерированный интерфейс {@link BoardApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BoardController implements BoardApi {

    private final GatewayRequestExecutor executor;
    private final BoardService boardService;

    /**
     * Получает структуру доски задач для конкретного проекта с учетом фильтров.
     * <p>
     * Эндпоинт является защищенным (PROTECTED). Перед выполнением метода проверяется
     * наличие доступа у пользователя к указанному проекту.
     * Возвращает задачи, сгруппированные по колонкам (статусам из workflow),
     * отсортированным согласно настройкам workflow.
     *
     * @param projectId   уникальный идентификатор проекта
     * @param issueType   тип задач (например, TASK, BUG), для которых нужно получить доску
     * @param assigneeId  (опционально) идентификатор пользователя для фильтрации задач по исполнителю
     * @param labelId     (опционально) идентификатор метки для фильтрации задач
     * @param includeDone (опционально) флаг включения в ответ задач со статусами закрытия (Done)
     * @param exchange    текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK) и структурой доски внутри {@link BoardResponseDto}
     */
    @Override
    public Mono<ResponseEntity<BoardResponseDto>> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                boardService.getBoard(
                        projectId,
                        issueType,
                        assigneeId,
                        labelId,
                        includeDone,
                        context
                ).map(ResponseEntity::ok)
        );
    }
}