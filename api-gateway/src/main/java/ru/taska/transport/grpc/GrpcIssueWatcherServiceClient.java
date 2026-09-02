package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AddIssueWatcherRequestDto;
import ru.taska.domain.dto.ListIssueWatchersResponseDto;
import ru.taska.domain.dto.UnwatchIssueResponseDto;
import ru.taska.domain.dto.WatchIssueResponseDto;
import ru.taska.mapper.IssueWatcherMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для watchers issue-service.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcIssueWatcherServiceClient {

    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final IssueWatcherMapper issueWatcherMapper;
    private final GrpcClientProperties properties;

    /**
     * Получает список подписчиков задачи.
     *
     * @param issueId идентификатор задачи
     * @param context контекст gateway-запроса
     * @return список подписчиков и общее количество
     */
    public Mono<ListIssueWatchersResponseDto> listIssueWatchers(
            String issueId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling listIssueWatchers", context.requestId());

        return dynamicStub().listIssueWatchers(
                ListIssueWatchersRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                ListIssueWatchersRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).map(issueWatcherMapper::toRestListWatchers);
    }

    /**
     * Подписывает текущего пользователя на задачу.
     *
     * @param issueId идентификатор задачи
     * @param context контекст gateway-запроса
     * @return результат подписки и актуальное число подписчиков
     */
    public Mono<WatchIssueResponseDto> watchIssueMe(
            String issueId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling watchIssueMe", context.requestId());

        return dynamicStub().watchIssue(
                WatchIssueRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                WatchIssueRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).map(issueWatcherMapper::toRestWatchIssueResponse);
    }

    /**
     * Отписывает текущего пользователя от задачи.
     *
     * @param issueId идентификатор задачи
     * @param context контекст gateway-запроса
     * @return результат отписки и актуальное число подписчиков
     */
    public Mono<UnwatchIssueResponseDto> unwatchIssueMe(
            String issueId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling unwatchIssueMe", context.requestId());

        return dynamicStub().unwatchIssue(
                UnwatchIssueRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                UnwatchIssueRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).map(issueWatcherMapper::toRestUnwatchIssueResponse);
    }

    /**
     * Добавляет подписчика на задачу.
     * Используется ADMIN-операция: actorUserId берется из JWT, targetUserId - из тела запроса.
     *
     * @param issueId идентификатор задачи
     * @param request тело запроса с пользователем для подписки
     * @param context контекст gateway-запроса
     * @return результат подписки и актуальное число подписчиков
     */
    public Mono<WatchIssueResponseDto> addIssueWatcher(
            String issueId,
            Mono<AddIssueWatcherRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling addIssueWatcher", context.requestId());

        return request.flatMap(requestDto ->
                dynamicStub().watchIssue(
                        WatchIssueRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        WatchIssueRequestBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setActorUserId(context.userContext().userId())
                                                .setTargetUserId(requestDto.getUserId().toString())
                                                .build()
                                )
                                .build()
                )
        ).map(issueWatcherMapper::toRestWatchIssueResponse);
    }

    /**
     * Удаляет подписчика задачи.
     *
     * @param issueId идентификатор задачи
     * @param targetUserId идентификатор пользователя, которого нужно отписать
     * @param context контекст gateway-запроса
     * @return результат отписки и актуальное число подписчиков
     */
    public Mono<UnwatchIssueResponseDto> removeIssueWatcher(
            String issueId,
            String targetUserId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling removeIssueWatcher", context.requestId());

        return dynamicStub().unwatchIssue(
                UnwatchIssueRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                UnwatchIssueRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setActorUserId(context.userContext().userId())
                                        .setTargetUserId(targetUserId)
                                        .build()
                        )
                        .build()
        ).map(issueWatcherMapper::toRestUnwatchIssueResponse);
    }

    /**
     * Возвращает gRPC stub с динамически заданным deadline из конфигурации.
     */
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub.withDeadlineAfter(
                properties.issueService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Формирует общий gRPC-заголовок из gateway-контекста.
     */
    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
