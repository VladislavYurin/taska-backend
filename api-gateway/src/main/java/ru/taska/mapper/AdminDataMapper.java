package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.ColumnMetadata;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.ServiceMetadata;
import ru.taska.api.admin.v1.TableMetadata;
import ru.taska.api.admin.v1.Value;
import ru.taska.domain.dto.ColumnMetadataDto;
import ru.taska.domain.dto.MetadataResponse;
import ru.taska.domain.dto.PaginationInfoDto;
import ru.taska.domain.dto.ReadOnlySingleRowResponseDto;
import ru.taska.domain.dto.ReadOnlyTableRowsResponseDto;
import ru.taska.domain.dto.ServiceMetadataDto;
import ru.taska.domain.dto.TableCapabilitiesDto;
import ru.taska.domain.dto.TableMetadataDto;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Основные методы-маперы toRestGetCatalogResponse и toRestListTableRowsResponse
 * Catalog включает в себя Service.
 * Service включает Table.
 * Table включает Column
 */

@Component
public class AdminDataMapper {

    /**
     * Преобразует gRPC GetCatalogResponse → REST MetadataResponse
     */
    public MetadataResponse toRestGetCatalogResponse(GetCatalogResponse grpcCatalogResponse) {
        if(grpcCatalogResponse==null || !grpcCatalogResponse.hasCatalog()){
            return new MetadataResponse();
        }
        List<ServiceMetadataDto> serviceMetadata = grpcCatalogResponse.getCatalog()
                .getServicesList()
                .stream()
                .map(this::toRestServiceMetadataDto) /// список serviceMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        MetadataResponse response = new MetadataResponse();
        response.setServices(serviceMetadata);
        return response;
    }

    /**
     * Преобразует gRPC ServiceMetadata → REST ServiceMetadataDto
     */
    private ServiceMetadataDto toRestServiceMetadataDto(ServiceMetadata grpcServiceMetadata) {
        ServiceMetadataDto dto = new ServiceMetadataDto();
        dto.setName(grpcServiceMetadata.getServiceKey());
        dto.setDatabaseAlias(grpcServiceMetadata.getAlias());

        List<TableMetadataDto> tables = grpcServiceMetadata.getTablesList()
                .stream()
                .map(this::toTableMetadataDto) /// список tableMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        dto.setTables(tables);
        return dto;
    }

    private TableMetadataDto toTableMetadataDto(TableMetadata grpcTableMetadata) {
        TableMetadataDto dto = new TableMetadataDto();
        dto.setName(grpcTableMetadata.getName());

        /// Преобразуем список колонок в рестовый список колонок
        List<ColumnMetadataDto> columns = grpcTableMetadata.getColumnsList()
                .stream()
                .map(this::toRestColumnMetadataDto) /// список columnMetadata преобразуем в рест ответ
                .collect(Collectors.toList());

        dto.setColumns(columns);

        /// Находим primary key
        grpcTableMetadata.getColumnsList().stream()
                .filter(ColumnMetadata::getPrimaryKey) /// находим колонки, где pk = true
                .findFirst()
                .ifPresent(pk -> dto.setPrimaryKey(pk.getName()));
        return dto;
    }

    /**
     * Преобразует gRPC ColumnMetadata → REST ColumnMetadataDto
     */
    private ColumnMetadataDto toRestColumnMetadataDto(ColumnMetadata grpcColumn) {
        ColumnMetadataDto dto = new ColumnMetadataDto();
        dto.setName(grpcColumn.getName());
        dto.setType(grpcColumn.getType());
        dto.setSensitive(grpcColumn.getSensitive());
        return dto;
    }

    /**
     * Преобразует gRPC ListTableRowsResponse → REST ReadOnlyTableRowsResponseDto
     */
    public ReadOnlyTableRowsResponseDto toRestListTableRowsResponse(ListTableRowsResponse grpcResponse) {
        ReadOnlyTableRowsResponseDto dto = new ReadOnlyTableRowsResponseDto();

        List<Map<String, Object>> data = grpcResponse.getRowsList()
                .stream()
                .map(this::rowToMap)
                .collect(Collectors.toList());
        dto.setData(data);

        PaginationInfoDto pagination = new PaginationInfoDto();
        pagination.setCurrentPage(grpcResponse.getPagination().getCurrentPage());
        pagination.setPageSize(grpcResponse.getPagination().getPageSize());
        pagination.setTotalRows(grpcResponse.getPagination().getTotalRows());
        pagination.setTotalPages(grpcResponse.getPagination().getTotalPages());
        pagination.setHasNext(grpcResponse.getPagination().getHasNext());
        pagination.setHasPrev(grpcResponse.getPagination().getHasPrev());
        dto.setPagination(pagination);

        TableCapabilitiesDto meta = new TableCapabilitiesDto();
        meta.setService(grpcResponse.getMeta().getServiceKey());
        meta.setTable(grpcResponse.getMeta().getTableName());
        meta.setColumns(grpcResponse.getMeta().getColumnsList());
        meta.setSortableColumns(grpcResponse.getMeta().getSortableColumnsList());
        meta.setFilterableColumns(grpcResponse.getMeta().getFilterableColumnsList());
        dto.setMeta(meta);

        return dto;
    }

    /**
     * Преобразует gRPC GetTableRowByIdResponse → REST ReadOnlySingleRowResponseDto
     */
    public ReadOnlySingleRowResponseDto toRestGetTableRowByIdResponse(
            ru.taska.api.admin.v1.GetTableRowByIdResponse grpcResponse) {
        ReadOnlySingleRowResponseDto dto = new ReadOnlySingleRowResponseDto();
        if (grpcResponse.hasRow()) {
            dto.setData(rowToMap(grpcResponse.getRow()));
        }
        return dto;
    }

    /**
     * Преобразует gRPC Row → Map<String, Object>
     */
    private Map<String, Object> rowToMap(Row grpcRow) {
        Map<String, Object> map = new HashMap<>();
        for (var entry : grpcRow.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), convertValue(entry.getValue()));
        }
        return map;
    }

    /**
     * Преобразует gRPC Value → Java Object
     */
    private Object convertValue(Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INT_VALUE -> value.getIntValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case TIMESTAMP_VALUE -> Instant.ofEpochSecond(value.getTimestampValue());
            default -> null;
        };
    }
}
