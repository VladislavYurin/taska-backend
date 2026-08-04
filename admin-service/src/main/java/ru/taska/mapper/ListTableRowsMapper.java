package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.FilterOperators;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.MetaInfo;
import ru.taska.api.admin.v1.PaginationInfo;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.Value;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Маппер для:
 *  Формирования gRPC ответа ListTableRowsResponse
 *
 */
@Component
public class ListTableRowsMapper {

    /**
     * Преобразует значения БД в gRPC Value.
     */
    public Value toGrpcValue(Object value) {
        Value.Builder builder = Value.newBuilder();

        // Устанавливаем null_value = true
        if (value == null) {
            builder.setNullValue(true);
            return builder.build(); // Возвращаем Value { null_value: true }
        }
        if (value instanceof String) {
            builder.setStringValue((String) value);
        // Безопасное приведение чисел (работает для Long, Integer, Short, Byte)
        }else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            builder.setIntValue(((Number) value).longValue());
        // Безопасное приведение дробных чисел (работает для Double, Float, BigDecimal)
        }else if (value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal) {
                builder.setDoubleValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            builder.setBoolValue((Boolean) value);
            // Поддержка всех популярных типов дат из реляционных БД
        }else if (value instanceof Instant) {
                builder.setTimestampValue(((Instant) value).getEpochSecond());
        } else if (value instanceof OffsetDateTime) {
            builder.setTimestampValue(((OffsetDateTime) value).toEpochSecond());
        } else if (value instanceof LocalDateTime) {
            builder.setTimestampValue(((LocalDateTime) value).toEpochSecond(ZoneOffset.UTC));
        } else {
            builder.setStringValue(value.toString());
        }
        return builder.build();
    }

    /// proto -> DTO
    public ListTableRowsRequestDto toRequestDto(ListTableRowsRequest req) {
        var body = req.getBody();

        //Преобразуем фильтры из FilterOperators (proto) в FilterOperatorsDto
        Map<String, FilterOperatorsDto> filters = req.getBody().getFiltersMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, e->toFilterOperatorsDto(e.getValue())
                ));

        return new ListTableRowsRequestDto(
                body.getServiceKey(),
                body.getTableName(),
                body.getPage(),
                body.getPageSize(),
                body.getSort(),
                body.getOrder(),
                filters
        );
    }

    private FilterOperatorsDto toFilterOperatorsDto(FilterOperators protoOperator) {
        return new FilterOperatorsDto(
                protoOperator.hasEquals() ? protoOperator.getEquals() : null,
                protoOperator.hasContains() ? protoOperator.getContains() : null,
                protoOperator.hasFrom() ? protoOperator.getFrom() : null,
                protoOperator.hasTo() ? protoOperator.getTo() : null
        );
    }

    /// DTO -> proto
    public ListTableRowsResponse toListTableRowsResponse(ListTableRowsResponseDto dto) {
        // Маппим rows (Map<String, Object>) → proto Row
        List<Row> maskedRows = dto.maskedRows().stream()
                .map(row -> {
                    Row.Builder builder = Row.newBuilder();
                    row.forEach((key, value) ->
                            builder.putFields(key, toGrpcValue(value))
                    );
                    return builder.build();
                })
                .toList();
        int totalPages = (int) Math.ceil((double) dto.total() / dto.pageSize());
        PaginationInfo pagination = PaginationInfo.newBuilder()
                .setCurrentPage(dto.page())
                .setPageSize(dto.pageSize())
                .setTotalRows(dto.total())
                .setTotalPages(totalPages)
                .setHasPrev(dto.page()>1)
                .setHasNext(dto.page()<totalPages)
                .build();
        MetaInfo metaInfo = MetaInfo.newBuilder()
                .setServiceKey(dto.serviceKey())
                .setTableName(dto.tableName())
                .addAllColumns(dto.columns())
//TODO:           .addAllSortableColumns()
//TODO:           .addAllFilterableColumns()
                .build();
        return ListTableRowsResponse
                .newBuilder()
                .addAllRows(maskedRows)
                .setPagination(pagination)
                .setMeta(metaInfo)
                .build();
    }
}
