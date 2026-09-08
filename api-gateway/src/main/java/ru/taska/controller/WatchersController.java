package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.WatchersApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AddIssueWatcherRequestDto;
import ru.taska.domain.dto.ListIssueWatchersResponseDto;
import ru.taska.domain.dto.UnwatchIssueResponseDto;
import ru.taska.domain.dto.WatchIssueResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueWatcherServiceClient;

import java.util.UUID;

/**
 * REST-контроллер для watchers задач.
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с issue-service через {@link GrpcIssueWatcherServiceClient}.
 */
@RestController
@RequiredArgsConstructor
public class WatchersController implements WatchersApi {

    private final GatewayRequestExecutor executor;
    private final GrpcIssueWatcherServiceClient grpcClient;

    /**
     * Получение списка подписчиков задачи.
     */
    @Override
    public Mono<ResponseEntity<ListIssueWatchersResponseDto>> listIssueWatchers(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.listIssueWatchers(issueId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Подписка текущего пользователя на задачу.
     */
    @Override
    public Mono<ResponseEntity<WatchIssueResponseDto>> watchIssueMe(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.watchIssueMe(issueId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Отписка текущего пользователя от задачи.
     */
    @Override
    public Mono<ResponseEntity<UnwatchIssueResponseDto>> unwatchIssueMe(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.unwatchIssueMe(issueId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Добавление подписчика на задачу (операция для project ADMIN).
     */
    @Override
    public Mono<ResponseEntity<WatchIssueResponseDto>> addIssueWatcher(
            UUID projectId,
            UUID issueId,
            Mono<AddIssueWatcherRequestDto> addIssueWatcherRequestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.addIssueWatcher(issueId.toString(), addIssueWatcherRequestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Удаление подписчика задачи (операция для project ADMIN).
     */
    @Override
    public Mono<ResponseEntity<UnwatchIssueResponseDto>> removeIssueWatcher(
            UUID projectId,
            UUID issueId,
            UUID userId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.removeIssueWatcher(issueId.toString(), userId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }
}
