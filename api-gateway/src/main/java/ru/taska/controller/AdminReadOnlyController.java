package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AdminApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcAdminServiceClient;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AdminReadOnlyController implements AdminApi {

    private final GatewayRequestExecutor executor;
    private final GrpcAdminServiceClient adminServiceClient;

    /**
     * GET /api/v1/admin/catalog
     * Возвращает каталог доступных сервисов и таблиц.
     */
    @Override
    public Mono<ResponseEntity<MetadataResponse>> getCatalog(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.getCatalog(context).map(ResponseEntity::ok)
        );
    }

    /**
     * GET /api/v1/admin/{service}/{table}
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
            Map<String, String> filter,
            ServerWebExchange exchange) {

        return executor.execute(exchange, EndpointSecurity.GLOBAL_ADMIN_REQUIRED, context ->
                adminServiceClient.listTableRows(service, table, page, pageSize, sort, order, filter, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * GET /api/v1/admin/{service}/{table}/{id}
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
}
