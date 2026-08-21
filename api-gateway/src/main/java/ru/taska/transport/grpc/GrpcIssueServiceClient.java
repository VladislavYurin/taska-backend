package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.AssignIssueRequestBody;
import ru.taska.api.issue.v1.CreateIssueLinkRequest;
import ru.taska.api.issue.v1.CreateIssueLinkRequestBody;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequestBody;
import ru.taska.api.issue.v1.DeleteIssueLinkBody;
import ru.taska.api.issue.v1.DeleteIssueLinkRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequestBody;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.GetIssueRequestBody;
import ru.taska.api.issue.v1.ListIssueLinksRequest;
import ru.taska.api.issue.v1.ListIssueLinksRequestBody;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesRequestBody;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.TransitionIssueRequestBody;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequestBody;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AssignIssueRequestDto;
import ru.taska.domain.dto.CreateIssueLinkRequestDto;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueLinkResponseDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssueLinksResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.TransitionIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import ru.taska.mapper.IssueMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с issue-service.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcIssueServiceClient {

    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final IssueMapper issueMapper;
    private final GrpcClientProperties properties;

    /**
     * Получает задачу вместе с историей изменений.
     *
     * @param issueId идентификатор задачи
     * @param context контекст запроса
     * @return задача с историей изменений
     */
    public Mono<IssueWithHistoryResponseDto> getIssue(
            String issueId,
            GatewayContext context
    ) {
        log.info("[{}] Calling getIssue", context.requestId());

        return dynamicStub().getIssue(
                        GetIssueRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        GetIssueRequestBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setActorUserId(context.userContext().userId())
                                                .build()
                                )
                                .build()
                )
                .map(issueMapper::toRestIssueWithHistoryResponse);
    }

    /**
     * Получает список задач проекта с фильтрацией и пагинацией.
     *
     * @param projectId  идентификатор проекта
     * @param status     фильтр по статусу (необязательное поле)
     * @param assigneeId фильтр по исполнителю (необязательное поле)
     * @param page       номер страницы (необязательное поле)
     * @param pageSize   размер страницы (необязательное поле)
     * @param context    контекст запроса
     * @return список задач
     */
    public Mono<ListIssuesResponseDto> listIssues(
            String projectId,
            String status,
            String assigneeId,
            Integer page,
            Integer pageSize,
            String labelId,
            GatewayContext context
    ) {
        log.info("[{}] Calling listIssues", context.requestId());

        var requestBodyBuilder = ListIssuesRequestBody.newBuilder()
                .setProjectId(projectId)
                .setActorUserId(context.userContext().userId());

        if (status != null) {
            requestBodyBuilder.setStatusKey(status);
        }

        if (assigneeId != null) {
            requestBodyBuilder.setAssigneeId(assigneeId);
        }

        if (page != null) {
            requestBodyBuilder.setPage(page);
        }

        if (pageSize != null) {
            requestBodyBuilder.setPageSize(pageSize);
        }
        if (labelId != null) {
            requestBodyBuilder.setLabelId(labelId);
        }

        return dynamicStub().listIssues(
                        ListIssuesRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(requestBodyBuilder.build())
                                .build()
                )
                .map(issueMapper::toRestListIssuesRequest);
    }

    /**
     * Создаёт новую задачу.
     *
     * @param projectId      идентификатор проекта
     * @param idempotencyKey ключ идемпотентности
     * @param request        параметры создаваемой задачи
     * @param context        контекст запроса
     * @return созданная задача
     */
    public Mono<IssueResponseDto> createIssue(
            String projectId,
            String idempotencyKey,
            Mono<CreateIssueRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling createIssue", context.requestId());

        return request.flatMap(requestDto ->
                        dynamicStub().createIssue(
                                CreateIssueRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                CreateIssueRequestBody.newBuilder()
                                                        .setIdempotencyKey(idempotencyKey)
                                                        .setProjectId(projectId)
                                                        .setIssueType(issueMapper.toGrpcIssueType(requestDto.getIssueType()))
                                                        .setSummary(requestDto.getSummary())
                                                        .setDescription(requestDto.getDescription())
                                                        .setPriority(issueMapper.toGrpcIssuePriority(requestDto.getPriority()))
                                                        .setReporterId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build())
                )
                .map(issueMapper::toRestIssueResponse);
    }

    /**
     * Назначает исполнителя задачи.
     *
     * @param issueId идентификатор задачи
     * @param request данные о назначаемом исполнителе
     * @param context контекст запроса
     * @return обновлённая задача
     */
    public Mono<IssueResponseDto> assignIssue(
            String issueId,
            Mono<AssignIssueRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling assignIssue", context.requestId());

        return request.flatMap(requestDto ->
                        dynamicStub().assignIssue(
                                AssignIssueRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                AssignIssueRequestBody.newBuilder()
                                                        .setIssueId(issueId)
                                                        .setAssigneeId(requestDto.getAssigneeId())
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .map(issueMapper::toRestIssueResponse);
    }

    /**
     * Обновляет основные поля задачи.
     *
     * @param issueId идентификатор задачи
     * @param request новые данные задачи
     * @param context контекст запроса
     * @return обновленные данные задачи
     */
    public Mono<UpdateIssueResponseDto> updateIssue(
            String issueId,
            Mono<UpdateIssueRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling updateIssue", context.requestId());

        return request.flatMap(requestDto ->
                        dynamicStub().updateIssue(
                                UpdateIssueRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                UpdateIssueRequestBody.newBuilder()
                                                        .setIssueId(issueId)
                                                        .setActorUserId(context.userContext().userId())
                                                        .setSummary(requestDto.getSummary())
                                                        .setDescription(requestDto.getDescription())
                                                        .setPriority(issueMapper.toGrpcIssuePriority(requestDto.getPriority()))
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .map(issueMapper::toRestUpdateResponse);
    }

    /**
     * Выполняет переход задачи по workflow.
     *
     * @param issueId      идентификатор задачи
     * @param transitionId идентификатор перехода
     * @param request      дополнительные данные перехода(необязательное поле)
     * @param context      контекст запроса
     * @return задача с обновлённой историей
     */
    public Mono<IssueWithHistoryResponseDto> transitionIssue(
            String issueId,
            String transitionId,
            Mono<TransitionIssueRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling transitionIssue", context.requestId());

        return request
                .defaultIfEmpty(new TransitionIssueRequestDto())
                .flatMap(requestDto -> {
                    var requestBodyBuilder = TransitionIssueRequestBody.newBuilder()
                            .setIssueId(issueId)
                            .setTransitionId(transitionId)
                            .setActorUserId(context.userContext().userId());

                    if (requestDto.getPayload() != null) {
                        requestBodyBuilder.setPayload(issueMapper.convertPayloadToJsonString(requestDto.getPayload()));
                    }

                    return dynamicStub().transitionIssue(
                            TransitionIssueRequest.newBuilder()
                                    .setHeader(buildGrpcHeader(context))
                                    .setBody(requestBodyBuilder.build())
                                    .build()
                    );

                })
                .map(issueMapper::toRestIssueWithHistoryResponse);
    }

    /**
     * Выполняет мягкое удаление задачи.
     *
     * @param issueId идентификатор задачи
     * @param context контекст запроса
     * @return сигнал об успешном завершении операции
     */
    public Mono<Void> deleteIssue(
            String issueId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteIssue", context.requestId());

        return dynamicStub().deleteIssue(
                        DeleteIssueRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        DeleteIssueRequestBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setActorUserId(context.userContext().userId())
                                                .build()
                                )
                                .build()
                )
                .then();
    }

    /**
     * Получает полный список связей для задачи.
     *
     * @param issueId идентификатор задачи
     * @param context контекст запроса
     * @return список задач
     */
    public Mono<ListIssueLinksResponseDto> listIssueLinks(
            String issueId,
            GatewayContext context
    ) {
        log.info("[{}] Calling listIssueLinks", context.requestId());

        return dynamicStub().listIssueLinks(
                        ListIssueLinksRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        ListIssueLinksRequestBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setActorUserId(context.userContext().userId())
                                                .build()
                                )
                                .build()
                )
                .map(issueMapper::toRestListIssueLinkResponse);
    }

    /**
     * Создает связь между задачами.
     *
     * @param issueId идентификатор задачи
     * @param request запрос с параметрами устанавливаемой связи
     * @param context контекст запроса
     * @return ответ с данными установленной связи
     */
    public Mono<IssueLinkResponseDto> createIssueLink(
            String issueId,
            Mono<CreateIssueLinkRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling createIssueLink", context.requestId());

        return request
                .flatMap(requestDto ->
                        dynamicStub().createIssueLink(
                                CreateIssueLinkRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                CreateIssueLinkRequestBody.newBuilder()
                                                        .setSourceIssueId(issueId)
                                                        .setTargetIssueId(requestDto.getTargetIssueId())
                                                        .setLinkType(issueMapper.toGrpcIssueLinkType(requestDto.getLinkType()))
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build())
                )
                .map(issueMapper::toRestIssueLinkResponse);
    }

    /**
     * Выполняет мягкое удаление связи.
     *
     * @param issueId идентификатор задачи
     * @param linkId  идентификатор связи
     * @param context контекст запроса
     * @return сигнал об успешном завершении операции
     */
    public Mono<Void> deleteIssueLink(
            String issueId,
            String linkId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteIssueLink", context.requestId());

        return dynamicStub().deleteIssueLink(
                        DeleteIssueLinkRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        DeleteIssueLinkBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setLinkId(linkId)
                                                .setActorUserId(context.userContext().userId())
                                                .build()
                                )
                                .build()
                )
                .then();
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub.withDeadlineAfter(properties.issueService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
