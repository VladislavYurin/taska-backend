package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AdminApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ProblematicOutboxEventsSummaryResponseDto;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcAdminServiceClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class AdminReadOnlyController implements AdminApi {

    private static final Set<String> NOT_FILTER_QUERY_PARAMS = Set.of("page", "pageSize", "sort", "order");

    private final GatewayRequestExecutor executor;
    private final GrpcAdminServiceClient adminServiceClient;

    /**
     * GET /api/v1/readonly/outbox/problematic-summary
     * Возвращает сводку проблемных outbox-событий.
     */
    @Override
    public Mono<ResponseEntity<ProblematicOutboxEventsSummaryResponseDto>> getProblematicOutboxEventsSummary(
            String serviceKey,
            ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.getProblematicOutboxEventsSummary(serviceKey, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * GET /api/v1/readonly/catalog
     * Возвращает каталог доступных сервисов и таблиц.
     */
    @Override
    public Mono<ResponseEntity<MetadataResponse>> getCatalog(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.getCatalog(context).map(ResponseEntity::ok)
        );
    }

    /**
     * GET /api/v1/readonly/{service}/{table}
     * Возвращает строки таблицы с пагинацией, сортировкой и фильтрами.
     */
    @Override
    public Mono<ResponseEntity<ReadOnlyTableRowsResponseDto>> listTableRows(
            String service,
            String table,
            Integer page,
            Integer pageSize,
            String sort,
            String order,
            Map<String, String> ignoredFilters,
            ServerWebExchange exchange) {

        Map<String, String> filters = extractColumnFilters(exchange);

        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.listTableRows(service, table, page, pageSize, sort, order, filters, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * GET /api/v1/readonly/{service}/{table}/{id}
     * Возвращает одну строку таблицы по ID.
     */
    @Override
    public Mono<ResponseEntity<ReadOnlySingleRowResponseDto>> getTableRowById(
            String service,
            String table,
            UUID id,
            ServerWebExchange exchange) {

        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.getTableRowById(service, table, id, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Все query-параметры URL, кроме page/pageSize/sort/order.
     * <p>
     * {@code toSingleValueMap()}: при дублях (?status.equals=a&status.equals=b) берётся одно значение.
     */
    static Map<String, String> extractColumnFilters(ServerWebExchange exchange) {
        Map<String, String> allParams = exchange.getRequest().getQueryParams().toSingleValueMap();
        return allParams.entrySet().stream()
                .filter(entry -> !NOT_FILTER_QUERY_PARAMS.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
