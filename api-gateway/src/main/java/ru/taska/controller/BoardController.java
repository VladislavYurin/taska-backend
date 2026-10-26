package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.BoardApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssuePriorityDto;
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
     * Возвращает доску проекта с учетом фильтров. Без параметра issueType
     * возвращаются задачи всех типов.
     *
     * @param projectId  идентификатор проекта
     * @param issueType  тип задач; без параметра — все типы
     * @param assigneeId фильтр по исполнителю
     * @param labelId фильтр по метке
     * @param includeDone включать ли завершенные задачи
     * @param priority фильтр по приоритету задачи
     * @param query полнотекстовый фильтр по ключу, краткому описанию и описанию задачи
     * @param exchange текущий HTTP exchange
     * @return сформированная доска проекта
     */
    @Override
    public Mono<ResponseEntity<BoardResponseDto>> getBoard(
            UUID projectId,
            String issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            IssuePriorityDto priority,
            String query,
            ServerWebExchange exchange
    ) {
        return executor.execute(
                exchange,
                EndpointSecurity.PROTECTED,
                context -> {
                    if (ru.taska.controller.utils.EnumFilterUtils.hasUnknownFilter(exchange, priority, issueType)) {
                        return Mono.just(ResponseEntity.ok(new BoardResponseDto().projectId(projectId)
                                .columns(java.util.List.of()).unplacedIssues(java.util.List.of())));
                    }
                    return boardService.getBoard(
                        projectId,
                        issueType,
                        assigneeId,
                        labelId,
                        includeDone,
                        priority,
                        query,
                        context
                ).map(ResponseEntity::ok);
                }
        );
    }
}
