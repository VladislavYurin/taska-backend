package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.FilterOperators;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.Row;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.mapper.ReadOnlyDataMapper;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.MetadataService;
import ru.taska.service.ReadOnlyQueryBuilder;
import validator.GrpcRequestValidators;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminService {

    private final MetadataService metadataService;
    private final MetadataCatalogMapper mapper;
    private final ReadOnlyQueryBuilder queryBuilder;
    private final ReadOnlyDataMapper dataMapper;
    private final ReadOnlyRepository readOnlyRepository;

    /**
     * Обрабатывает gRPC-запрос на получение каталога метаданных.
     */
    public Mono<GetCatalogResponse> getCatalog(Mono<GetCatalogRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    log.info("[{}][{}] getCatalog", requestId, nodeId);

                    return metadataService.getCatalog()
                            .map(mapper::toGetCatalogResponse);
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
    public Mono<ListTableRowsResponse> listTableRows(Mono<ListTableRowsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();

                    log.info("[{}][{}] listTableRows: service={}, table={}, page={}, pageSize={},filters={}",
                            requestId, nodeId,
                            req.getBody().getServiceKey(),
                            req.getBody().getTableName(),
                            req.getBody().getPage(),
                            req.getBody().getPageSize(),
                            req.getBody().getFiltersMap());

                    String serviceKey = req.getBody().getServiceKey(); // "user-service"
                    String tableName = req.getBody().getTableName();  // "users"
                    Map<String, FilterOperators> filters = req.getBody().getFiltersMap();

                    try {
                        /// Построение безопасного SQL запроса (с проверкой allowlist)
                        ReadOnlyQueryBuilder.SqlQuery safeQuery = queryBuilder.buildSafeQuery(req);
                        /// Строим COUNT запрос (для пагинации)
                        ReadOnlyQueryBuilder.SqlQuery safeCountQuery = queryBuilder.buildSafeCountQuery(tableName, filters);

                        /// Выполнение запроса к БД через DatabaseClient (параметризованный!)
                        // ReadOnlyRepository:
                        // - Берет DatabaseClient из ReadonlyR2dbcConfig
                        // - Выполняет параметризованный запрос
                        // - Возвращает Flux<Map<String, Object>> - строки таблицы
                        return readOnlyRepository.executeQuery(serviceKey,safeQuery)
                                .collectList()
                                .flatMap(rows -> {
                                    ///Считаем общее количество записей (нужно для пагинации)
                                    return readOnlyRepository.countRows(serviceKey, safeCountQuery)
                                            .flatMap(total->{
                                                /// Маскировка sensitive колонок
                                                List<Row> maskedRows = dataMapper.maskSensitiveColumns(
                                                        rows,           // строки из БД
                                                        serviceKey,     // "user-service"
                                                        tableName       // "users"
                                                );

                                                return metadataService.getTableColumns(serviceKey,tableName)
                                                        .map(allColumns-> dataMapper.buildResponse(req,maskedRows,total,allColumns));
                                            });
                                });
                    }catch (IllegalArgumentException e) {
                        /// Если что-то пошло не так (неправильная таблица, колонка и т.д.)
                        log.warn("[{}][{}] listTableRows error: {}", requestId, nodeId, e.getMessage());
                        return Mono.error(new StatusRuntimeException(
                                Status.INVALID_ARGUMENT.withDescription(e.getMessage())
                        ));
                    }
                }));
    }
}
