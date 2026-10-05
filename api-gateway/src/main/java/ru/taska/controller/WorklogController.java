package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.WorklogsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AddIssueWorklogRequestDto;
import ru.taska.domain.dto.IssueWorklogResponseDto;
import ru.taska.domain.dto.ListIssueWorklogsResponseDto;
import ru.taska.domain.dto.UpdateIssueWorklogRequestDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueWorklogServiceClient;

import java.util.UUID;

/**
 * REST-контроллер для работы с worklog у задач.
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с issue-сервисом через {@link GrpcIssueWorklogServiceClient}.
 *
 * projectId в пути используется только для REST-иерархии/читаемости URL и не участвует в авторизации.
 */
@RestController
@RequiredArgsConstructor
public class WorklogController implements WorklogsApi {
    private final GatewayRequestExecutor executor;
    private final GrpcIssueWorklogServiceClient worklogClient;

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<IssueWorklogResponseDto>> addIssueWorklog(
            UUID projectId,
            UUID issueId,
            Mono<AddIssueWorklogRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                worklogClient.addIssueWorklog(
                        issueId.toString(),
                        requestDto,
                        context
                ).map(responseBody ->
                        ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
                ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<Void>> deleteIssueWorklog(
            UUID projectId,
            UUID issueId,
            UUID worklogId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                worklogClient.deleteIssueWorklog(
                        issueId.toString(),
                        worklogId.toString(),
                        context
                ).thenReturn(ResponseEntity.noContent().build()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<ListIssueWorklogsResponseDto>> listIssueWorklogs(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                worklogClient.listIssueWorklogs(
                        issueId.toString(),
                        context
                ).map(ResponseEntity::ok));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<IssueWorklogResponseDto>> updateIssueWorklog(
            UUID projectId,
            UUID issueId,
            UUID worklogId,
            Mono<UpdateIssueWorklogRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                worklogClient.updateIssueWorklog(
                        issueId.toString(),
                        worklogId.toString(),
                        request,
                        context
                ).map(ResponseEntity::ok));
    }

}
