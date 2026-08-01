package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ReadonlyApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ReadOnlyResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcAdminServiceClient;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class AdminReadOnlyController implements ReadonlyApi {

    private final GatewayRequestExecutor executor;
    private final GrpcAdminServiceClient adminServiceClient;
    private static final Set<String> SYSTEM_PARAMS = Set.of("page", "pageSize", "sort", "order");



    /**
     * Эндпоинт для получения метаданных (каталог сервисов и таблиц)
     * GET /api/v1/readonly/metadata
     * Вызывает gRPC метод getCatalog()
     */
    @Override
    public Mono<ResponseEntity<MetadataResponse>> getMetadata(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED,context ->
            adminServiceClient.getCatalog(context)
                    .map(ResponseEntity::ok)
        );


    }

    /**
     * Эндпоинт для получения данных таблицы
     * GET /api/v1/readonly/{service}/{table}
     * Вызывает gRPC метод listTableRows()
     */
    @Override
    public Mono<ResponseEntity<ReadOnlyResponseDto>> listTableRows(
            String service,
            String table,
            Integer page,
            Integer pageSize,
            @Nullable String sort,
            String order,
            @Nullable Map<String, String> ignoredFilter,
            ServerWebExchange exchange) {


        /// Получаем все параметры из URL в виде Map<String, String>
        Map<String, String> allParams = exchange.getRequest().getQueryParams().toSingleValueMap();
        //toSingleValueMap() -> придет ?status=active&status=blocked, бэкенд обработает только active,
        /// Отфильтровываем системные параметры, оставляя только фильтры колонок
        Map<String, String> columnFilters = allParams.entrySet().stream()
                .filter(entry -> !SYSTEM_PARAMS.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return executor.execute(exchange,EndpointSecurity.GLOBAL_ADMIN_REQUIRED,context ->
                adminServiceClient.listTableRows(service, table, page, pageSize, sort, order, columnFilters, context)
                        .map(ResponseEntity::ok)
        );
    }
}
