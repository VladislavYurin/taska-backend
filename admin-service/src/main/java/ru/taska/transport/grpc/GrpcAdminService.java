package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.admin.v1.GetCatalogRequest;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.GetTableRowByIdRequest;
import ru.taska.api.admin.v1.GetTableRowByIdResponse;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.mapper.ListTableRowsMapper;
import ru.taska.mapper.MetadataCatalogMapper;
import ru.taska.service.AdminService;
import ru.taska.service.MetadataService;
import validator.GrpcRequestValidators;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminService {

    private final MetadataService metadataService;
    private final MetadataCatalogMapper mapper;
    private final AdminService adminService;
    private final ListTableRowsMapper listTableRowsMapper;

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

                    log.info("[{}][{}] listTableRows: service={}, table={}, page={}, pageSize={}, filters={}",
                            requestId, nodeId,
                            req.getBody().getServiceKey(),
                            req.getBody().getTableName(),
                            req.getBody().hasPage() ? req.getBody().getPage() : null,
                            req.getBody().hasPageSize() ? req.getBody().getPageSize() : null,
                            req.getBody().getFiltersMap());

                    // Маппим proto → DTO
                    ListTableRowsRequestDto requestDto = listTableRowsMapper.toRequestDto(req);

                    // Бизнес-логика
                    return adminService.listTableRows(requestDto)
                            .map(listTableRowsMapper::toListTableRowsResponse);
                }));
    }

    /**
     * Обрабатывает gRPC-запрос на получение одной строки таблицы по ID.
     */
    public Mono<GetTableRowByIdResponse> getTableRowById(Mono<GetTableRowByIdRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();

                    log.info("[{}][{}] getTableRowById: service={}, table={}, id={}",
                            requestId, nodeId,
                            req.getBody().getServiceKey(),
                            req.getBody().getTableName(),
                            req.getBody().getId());

                    return adminService.getTableRowById(listTableRowsMapper.toGetByIdRequestDto(req))
                            .map(listTableRowsMapper::toGetTableRowByIdResponse);
                }));
    }
}
