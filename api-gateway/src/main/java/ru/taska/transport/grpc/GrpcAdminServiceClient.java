package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequest;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryRequestBody;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdRequestBody;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ReactorAdminReadonlyServiceGrpc;
import ru.taska.api.admin.v1.RetryOutboxEventRequest;
import ru.taska.api.admin.v1.RetryOutboxEventRequestBody;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ProblematicOutboxEventsSummaryResponseDto;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.domain.dto.RetryOutboxEventRequestDto;
import ru.taska.domain.dto.RetryOutboxEventResponseDto;
import ru.taska.mapper.AdminDataMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcAdminServiceClient {

    private final ReactorAdminReadonlyServiceGrpc.ReactorAdminReadonlyServiceStub adminServiceStub;
    private final AdminDataMapper mapper;
    private final GrpcClientProperties properties;

    public Mono<MetadataResponse> getCatalog(GatewayContext context) {
        log.debug("[{}] Calling getCatalog", context.requestId());

        GetCatalogRequest getCatalogRequest = GetCatalogRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .build();

        return dynamicStub().getCatalog(getCatalogRequest)
                .map(mapper::toRestGetCatalogResponse);
    }

    public Mono<ReadOnlyTableRowsResponseDto> listTableRows(
            String service,
            String table,
            Integer page,
            Integer pageSize,
            String sort,
            String order,
            Map<String, String> filters,
            GatewayContext context
    ) {
        log.debug("[{}] Calling listTableRows", context.requestId());

        ListTableRowsRequestBody.Builder bodyBuilder = ListTableRowsRequestBody.newBuilder()
                .setServiceKey(service)
                .setTableName(table);

        if (page != null) bodyBuilder.setPage(page);
        if (pageSize != null) bodyBuilder.setPageSize(pageSize);
        if (sort != null) bodyBuilder.setSort(sort);
        if (order != null) bodyBuilder.setOrder(order);
        if (filters != null && !filters.isEmpty()) bodyBuilder.putAllFilters(filters);

        ListTableRowsRequestBody listTableRowsRequestBody = bodyBuilder.build();

        ListTableRowsRequest listTableRowsRequest = ListTableRowsRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(listTableRowsRequestBody)
                .build();

        return dynamicStub().listTableRows(listTableRowsRequest)
                .map(mapper::toRestListTableRowsResponse);
    }

    public Mono<ReadOnlySingleRowResponseDto> getTableRowById(
            String service,
            String table,
            UUID id,
            GatewayContext context
    ) {
        log.debug("[{}] Calling getTableRowById", context.requestId());

        GetTableRowByIdRequestBody body = GetTableRowByIdRequestBody.newBuilder()
                .setServiceKey(service)
                .setTableName(table)
                .setId(id.toString())
                .build();

        GetTableRowByIdRequest request = GetTableRowByIdRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(body)
                .build();

        return dynamicStub().getTableRowById(request)
                .map(mapper::toRestGetTableRowByIdResponse);
    }

    public Mono<ProblematicOutboxEventsSummaryResponseDto> getProblematicOutboxEventsSummary(
            String serviceKey,
            GatewayContext context
    ) {
        log.debug("[{}] Calling getProblematicOutboxEventsSummary", context.requestId());

        GetProblematicOutboxEventsSummaryRequestBody.Builder bodyBuilder =
                GetProblematicOutboxEventsSummaryRequestBody.newBuilder();

        if (serviceKey != null) {
            bodyBuilder.setServiceKey(serviceKey);
        }

        GetProblematicOutboxEventsSummaryRequest request =
                GetProblematicOutboxEventsSummaryRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(bodyBuilder.build())
                        .build();

        return dynamicStub().getProblematicOutboxEventsSummary(request)
                .map(mapper::toRestProblematicOutboxEventsSummaryResponse);
    }

    /**
     * Выполняет ручной retry outbox-события через admin-service.
     * <p>
     * Данные REST-запроса извлекаются из {@link RetryOutboxEventRequestDto},
     * а actor context формируется из проверенного {@link GatewayContext}.
     *
     * @param service    сервис-владелец outbox
     * @param eventId    идентификатор outbox-события
     * @param requestDto REST-запрос с причиной ручного retry
     * @param context    проверенный контекст GLOBAL_ADMIN
     * @return состояние события после retry
     */
    public Mono<RetryOutboxEventResponseDto> retryOutboxEvent(
            String service,
            UUID eventId,
            RetryOutboxEventRequestDto requestDto,
            GatewayContext context
    ) {
        log.debug(
                "[{}] Calling retryOutboxEvent: service={}, eventId={}",
                context.requestId(),
                service,
                eventId
        );

        var userContext = context.userContext();

        RetryOutboxEventRequestBody body =
                RetryOutboxEventRequestBody.newBuilder()
                        .setServiceKey(service)
                        .setEventId(eventId.toString())
                        .setReason(requestDto.getReason())
                        .setActorUserId(userContext.userId())
                        .setActorLogin(userContext.login())
                        .addActorRoles(userContext.globalRole().name())
                        .build();

        RetryOutboxEventRequest request =
                RetryOutboxEventRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(body)
                        .build();

        return dynamicStub()
                .retryOutboxEvent(request)
                .map(mapper::toRestRetryOutboxEventResponse);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorAdminReadonlyServiceGrpc.ReactorAdminReadonlyServiceStub dynamicStub() {
        return adminServiceStub.withDeadlineAfter(
                properties.adminService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
