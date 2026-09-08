package ru.taska.controller;

import lombok.RequiredArgsConstructor;
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
 * REST-контроллер для получения доски задач проекта.
 */
@RestController
@RequiredArgsConstructor
public class BoardController implements BoardApi {

    private final GatewayRequestExecutor executor;
    private final BoardService boardService;

    /**
     * Возвращает доску проекта для указанного типа задач с учетом фильтров.
     *
     * @param projectId идентификатор проекта
     * @param issueType тип задач
     * @param assigneeId фильтр по исполнителю
     * @param labelId фильтр по метке
     * @param includeDone включать ли завершенные задачи
     * @param exchange текущий HTTP exchange
     * @return сформированная доска проекта
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
        return executor.execute(
                exchange,
                EndpointSecurity.PROTECTED,
                context -> boardService.getBoard(
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