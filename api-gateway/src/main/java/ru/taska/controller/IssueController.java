package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.IssueApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AssignIssueRequestDto;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.TransitionIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueServiceClient;

/**
 * REST-контроллер для работы с задачами.
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с issue-сервисом через {@link GrpcIssueServiceClient}.
 */
@RestController
@RequiredArgsConstructor
public class IssueController implements IssueApi {

    private final GatewayRequestExecutor executor;
    private final GrpcIssueServiceClient issueClient;

    /**
     * Создаёт новую задачу в указанном проекте.
     */
    @Override
    public Mono<ResponseEntity<IssueResponseDto>> createIssue(
            String projectId,
            String idempotencyKey,
            Mono<CreateIssueRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.createIssue(projectId, idempotencyKey, request, context)
                        .map(responseBody ->
                                ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
                        )
        );
    }

    /**
     * Возвращает задачу вместе с историей изменений.
     */
    @Override
    public Mono<ResponseEntity<IssueWithHistoryResponseDto>> getIssue(
            String issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.getIssue(issueId, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Возвращает список задач проекта с учётом фильтров и пагинации.
     */
    @Override
    public Mono<ResponseEntity<ListIssuesResponseDto>> listIssues(
            String projectId,
            String status,
            String assigneeId,
            Integer page,
            Integer pageSize,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.listIssues(projectId, status, assigneeId, page, pageSize, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Назначает исполнителя задачи.
     */
    @Override
    public Mono<ResponseEntity<IssueResponseDto>> assignIssue(
            String issueId,
            Mono<AssignIssueRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.assignIssue(issueId, request, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Обновляет основные поля задачи.
     */
    @Override
    public Mono<ResponseEntity<UpdateIssueResponseDto>> updateIssue(
            String issueId,
            Mono<UpdateIssueRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.updateIssue(issueId, request, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Выполняет переход задачи по workflow.
     */
    @Override
    public Mono<ResponseEntity<IssueWithHistoryResponseDto>> transitionIssue(
            String issueId,
            String transitionId,
            Mono<TransitionIssueRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.transitionIssue(issueId, transitionId, request, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Выполняет мягкое удаление задачи.
     */
    @Override
    public Mono<ResponseEntity<Void>> deleteIssue(
            String issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                issueClient.deleteIssue(issueId, context)
                        .thenReturn(ResponseEntity.noContent().build())
        );
    }

}

