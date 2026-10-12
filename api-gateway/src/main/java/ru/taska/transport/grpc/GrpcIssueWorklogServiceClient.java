package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AddIssueWorklogRequestDto;
import ru.taska.domain.dto.IssueWorklogResponseDto;
import ru.taska.domain.dto.ListIssueWorklogsResponseDto;
import ru.taska.domain.dto.UpdateIssueWorklogRequestDto;
import ru.taska.mapper.IssueWorklogMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с issue-service.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcIssueWorklogServiceClient {
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final IssueWorklogMapper issueWorklogMapper;
    private final GrpcClientProperties properties;

    /**
     * Добавляет worklog к задаче.
     */
    public Mono<IssueWorklogResponseDto> addIssueWorklog(
            String issueId,
            Mono<AddIssueWorklogRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling addIssueWorklog", context.requestId());

        return request
                .map(requestDto -> issueWorklogMapper.toAddIssueWorklogGrpcRequest(issueId, requestDto, context))
                .flatMap(grpcRequest -> dynamicStub().addIssueWorklog(grpcRequest))
                .map(AddIssueWorklogResponse::getWorklog)
                .map(issueWorklogMapper::toRestIssueWorklog);
    }

    /**
     * Удаляет worklog задачи.
     */
    public Mono<Void> deleteIssueWorklog(
            String issueId,
            String worklogId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteIssueWorklog", context.requestId());

        DeleteIssueWorklogRequest grpcRequest = issueWorklogMapper
                .toDeleteIssueWorklogGrpcRequest(issueId, worklogId, context);

        return dynamicStub().deleteIssueWorklog(grpcRequest)
                .then();
    }

    /**
     * Получает список worklogs задачи.
     */
    public Mono<ListIssueWorklogsResponseDto> listIssueWorklogs(
            String issueId,
            GatewayContext context
    ) {
        log.info("[{}] Calling listIssueWorklogs", context.requestId());

        ListIssueWorklogsRequest grpcRequest = issueWorklogMapper
                .toListIssueWorklogsGrpcRequest(issueId, context);

        return dynamicStub().listIssueWorklogs(grpcRequest)
                .map(issueWorklogMapper::toRestListIssueWorklogs);
    }

    /**
     * Обновляет worklog задачи. Непереданные поля не изменяются.
     */
    public Mono<IssueWorklogResponseDto> updateIssueWorklog(
            String issueId,
            String worklogId,
            Mono<UpdateIssueWorklogRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling updateIssueWorklog", context.requestId());

        return request
                .map(requestDto -> issueWorklogMapper.toUpdateIssueWorklogGrpcRequest(issueId, worklogId, requestDto, context))
                .flatMap(grpcRequest -> dynamicStub().updateIssueWorklog(grpcRequest))
                .map(UpdateIssueWorklogResponse::getWorklog)
                .map(issueWorklogMapper::toRestIssueWorklog);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub.withDeadlineAfter(properties.issueService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
}
