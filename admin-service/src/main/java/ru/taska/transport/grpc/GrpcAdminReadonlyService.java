package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryResponse;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.RetryOutboxEventRequest;
import ru.taska.api.admin.v1.RetryOutboxEventResponse;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.mapper.ListTableRowsMapper;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.mapper.OutboxRetryMapper;
import ru.taska.mapper.ProblematicOutboxEventMapper;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;
import ru.taska.service.OutboxRetryService;
import ru.taska.service.ProblematicOutboxEventService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import validator.GrpcRequestValidators;

import java.util.UUID;

import static ru.taska.transport.grpc.logging.GrpcAdminLogging.logOnError;
import static ru.taska.transport.grpc.logging.GrpcAdminLogging.logValidationError;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminReadonlyService {

    private final MetadataService metadataService;
    private final MetadataCatalogMapper mapper;
    private final AdminReadonlyService adminReadonlyService;
    private final ListTableRowsMapper listTableRowsMapper;
    private final ProblematicOutboxEventService problematicOutboxEventService;
    private final ProblematicOutboxEventMapper problematicOutboxEventMapper;
    private final OutboxRetryService outboxRetryService;
    private final ObjectMapper objectMapper;
    private final OutboxRetryMapper outboxRetryMapper;

    /**
     * Обрабатывает gRPC-запрос на получение каталога метаданных.
     */
    @TrackMetrics(counter = "admin-service_get-catalog_grpc_counter",
            timer = "admin-service_get-catalog_grpc_timer")
    public Mono<GetCatalogResponse> getCatalog(Mono<GetCatalogRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).doOnError(logValidationError(
                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "getCatalog"
                )).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    log.info("[{}][{}] getCatalog", requestId, nodeId);

                    return metadataService.getCatalog()
                            .map(mapper::toGetCatalogResponse)
                            .doOnSuccess(result ->
                                    log.info("[{}][{}] getCatalog: successfully retrieved catalog",
                                            requestId, nodeId)
                            )
                            .doOnError(logOnError(requestId, nodeId, "getCatalog"));
                }));
    }

    /**
     * listTableRows
     * 1. Принимает gRPC запрос от API Gateway
     * 2. Строит безопасный SQL запрос
     * 3. Выполняет запрос к БД
     * 4. Маскирует sensitive данные
     * 5. Возвращает gRPC ответ
     */
    @TrackMetrics(counter = "admin-service_list-table-rows_grpc_counter",
            timer = "admin-service_list-table-rows_grpc_timer")
    public Mono<ListTableRowsResponse> listTableRows(Mono<ListTableRowsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).doOnError(logValidationError(
                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listTableRows"
                )).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();

                    log.info("[{}][{}] listTableRows: service={}, table={}, page={}, pageSize={}, filters={}",
                            requestId, nodeId,
                            req.getBody().getServiceKey(),
                            req.getBody().getTableName(),
                            req.getBody().hasPage() ? req.getBody().getPage() : null,
                            req.getBody().hasPageSize() ? req.getBody().getPageSize() : null,
                            req.getBody().getFiltersMap());

                    ListTableRowsRequestDto requestDto = listTableRowsMapper.toRequestDto(req);

                    return adminReadonlyService.listTableRows(requestDto, requestId, nodeId)
                            .map(listTableRowsMapper::toListTableRowsResponse)
                            .doOnSuccess(result ->
                                    log.info("[{}][{}] listTableRows: successfully found {} rows",
                                            requestId, nodeId, result != null ? result.getRowsCount() : 0)
                            )
                            .doOnError(logOnError(requestId, nodeId, "listTableRows"));
                }));
    }

    /**
     * Обрабатывает gRPC-запрос на получение одной строки таблицы по ID.
     */
    @TrackMetrics(counter = "admin-service_get-table-row-by-id_grpc_counter",
            timer = "admin-service_get-table-row-by-id_grpc_timer")
    public Mono<GetTableRowByIdResponse> getTableRowById(Mono<GetTableRowByIdRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).doOnError(logValidationError(
                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "getTableRowById"
                )).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();

                    log.info("[{}][{}] getTableRowById: service={}, table={}, id={}",
                            requestId, nodeId,
                            req.getBody().getServiceKey(),
                            req.getBody().getTableName(),
                            req.getBody().getId());

                    return adminReadonlyService.getTableRowById(
                                    listTableRowsMapper.toGetByIdRequestDto(req),
                                    requestId,
                                    nodeId
                            )
                            .map(listTableRowsMapper::toGetTableRowByIdResponse)
                            .doOnSuccess(result ->
                                    log.info(
                                            "[{}][{}] getTableRowById: successfully found row, service={}, table={}, id={}",
                                            requestId,
                                            nodeId,
                                            req.getBody().getServiceKey(),
                                            req.getBody().getTableName(),
                                            req.getBody().getId()
                                    )
                            )
                            .doOnError(logOnError(requestId, nodeId, "getTableRowById"));
                }));
    }

    /**
     * Обрабатывает gRPC-запрос на получение проблемных outbox-событий.
     */
    @TrackMetrics(counter = "admin-service_get-problematic-outbox-events-summary_grpc_counter",
            timer = "admin-service_get-problematic-outbox-events-summary_grpc_timer")
    public Mono<GetProblematicOutboxEventsSummaryResponse> getProblematicOutboxEventsSummary(
            Mono<GetProblematicOutboxEventsSummaryRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).doOnError(logValidationError(
                        req.getHeader().getRequestId(),
                        req.getHeader().getNodeId(),
                        "getProblematicOutboxEventsSummary"
                )).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String serviceKey =
                            req.getBody().hasServiceKey()
                                    ? req.getBody().getServiceKey()
                                    : null;

                    log.info(
                            "[{}][{}] getProblematicOutboxEventsSummary: serviceKey={}",
                            requestId,
                            nodeId,
                            serviceKey
                    );

                    return problematicOutboxEventService
                            .getProblematicOutboxEventsSummary(serviceKey, requestId, nodeId)
                            .map(problematicOutboxEventMapper::toProto)
                            .doOnSuccess(result ->
                                    log.info(
                                            "[{}][{}] getProblematicOutboxEventsSummary: successfully retrieved summary",
                                            requestId,
                                            nodeId
                                    )
                            )
                            .doOnError(logOnError(
                                    requestId,
                                    nodeId,
                                    "getProblematicOutboxEventsSummary"
                            ));
                }));
    }

    /**
     * Выполняет административный retry outbox-события.
     * <p>
     * Transport-слой валидирует обязательные поля запроса,
     * преобразует actor context в формат бизнес-сервиса
     * и делегирует выполнение {@link OutboxRetryService}.
     *
     * @param request gRPC-запрос на retry outbox-события
     * @return состояние события после административного retry
     */
    @TrackMetrics(counter = "admin-service_retry-outbox-event_grpc_counter",
            timer = "admin-service_retry-outbox-event_grpc_timer")
    public Mono<RetryOutboxEventResponse> retryOutboxEvent(
            Mono<RetryOutboxEventRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(),
                                        "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(),
                                        "header.nodeId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getServiceKey(),
                                        "body.serviceKey"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getEventId(),
                                        "body.eventId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getReason(),
                                        "body.reason"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(),
                                        "body.actorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getActorLogin(),
                                        "body.actorLogin"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        String.join(",", req.getBody().getActorRolesList()),
                                        "body.actorRoles"
                                )
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                "retryOutboxEvent"
                        ))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            String serviceKey = t.getT3();
                            UUID eventId = t.getT4();
                            String reason = t.getT5();
                            UUID actorUserId = t.getT6();
                            String actorLogin = t.getT7();

                            JsonNode actorRoles = objectMapper.valueToTree(
                                    req.getBody().getActorRolesList()
                            );

                            log.info(
                                    "[{}][{}] retryOutboxEvent: service={}, eventId={}",
                                    requestId,
                                    nodeId,
                                    serviceKey,
                                    eventId
                            );

                            return outboxRetryService.retryOutboxEvent(
                                            requestId,
                                            nodeId,
                                            serviceKey,
                                            eventId,
                                            reason,
                                            actorUserId,
                                            actorLogin,
                                            actorRoles
                                    )
                                    .doOnSuccess(snapshot ->
                                            log.info(
                                                    "[{}][{}] retryOutboxEvent: successfully retried, service={}, eventId={}",
                                                    requestId,
                                                    nodeId,
                                                    serviceKey,
                                                    eventId
                                            )
                                    )
                                    .doOnError(logOnError(
                                            requestId,
                                            nodeId,
                                            "retryOutboxEvent"
                                    ))
                                    .map(outboxRetryMapper::toRetryOutboxEventResponse);
                        }));
    }
}
