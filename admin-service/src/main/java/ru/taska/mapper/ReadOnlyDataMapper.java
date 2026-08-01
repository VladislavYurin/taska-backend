package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.MetaInfo;
import ru.taska.api.admin.v1.PaginationInfo;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.Value;
import ru.taska.config.props.MetadataCatalogProperties;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Маппер для:
 * 1. Маскировки sensitive колонок
 * 2. Формирования gRPC ответа ListTableRowsResponse
 *
 * Поддерживаемые типы sensitive данных:
 * 1. Пароли, хеши, токены, секреты → ПОЛНАЯ МАСКА ("***")
 * 2. Email → ЧАСТИЧНАЯ МАСКА (jo***@gmail.com)
 * - Общие строки: some_value → so***ue (частичная)
 */
@Component
@RequiredArgsConstructor
public class ReadOnlyDataMapper {

    private final MetadataCatalogProperties properties;

    /**
     * Маскирует sensitive колонки в строках.
     *
     * @param rows строки из БД в виде [columnName,value]
     * @param serviceKey ключ сервиса
     * @param tableName имя таблицы
     * @return List<Row> - строки с замаскированными sensitive колонками
     */
    public List<Row> maskSensitiveColumns(
            List<Map<String, Object>> rows,
            String serviceKey,
            String tableName
    ) {
        // 1. Получаем sensitive колонки из конфига
        var sensitiveColumns = getSensitiveColumns(serviceKey, tableName);

        // 2. Маскируем каждую чувствительную строку
        return rows.stream()
                .map(row -> {
                    Row.Builder rowBuilder = Row.newBuilder();

                    for (var entry : row.entrySet()) {
                        String columnName = entry.getKey();
                        Object value = entry.getValue();

                        // Если колонка sensitive -> маскируем
                        if (sensitiveColumns.contains(columnName)) {
                            value = maskValue(value);
                        }

                        rowBuilder.putFields(columnName, toGrpcValue(value));
                    }

                    return rowBuilder.build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Получает список sensitive колонок из конфига.
     *
     * Пример: из ["users.email", "users.phone"] → ["email", "phone"]
     */
    private Set<String> getSensitiveColumns(String serviceKey, String tableName) {
        var serviceProps = properties.services().get(serviceKey);
        // Проверяем всю цепочку на null, так как пустые списки в YAML могут парситься как null
        if (serviceProps.tables().sensitiveColumns() == null){
            return Collections.emptySet();
        }

        String prefix = tableName + ".";
        return serviceProps.tables().sensitiveColumns().stream()
                .filter(column -> column.startsWith(prefix))
                .map(column -> column.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    /**
     * Маскирует значение (все чувствительные значения сейчас маскируются "***")
     */
    private Object maskValue(Object value) {
        ///TODO: null пока остается null, как-то обрабатывать? Если у нас не будет обрабатываться null, то
        if (value == null) {
            return null;
        }
        return "***";
    }

    /**
     * Преобразует значение из бд в gRPC Value.
     */
    private Value toGrpcValue(Object value) {
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

    public ListTableRowsResponse buildResponse(
            ListTableRowsRequest req,
            List<Row> maskedRows,
            Long total,
            List<String> allColumns
    ) {
        int page = req.getBody().getPage();
        int pageSize = req.getBody().getPageSize();
        int totalPages = (int) Math.ceil((double) total /pageSize);

        PaginationInfo pagination = PaginationInfo.newBuilder()
                .setCurrentPage(page)
                .setPageSize(pageSize)
                .setTotalRows(total)
                .setTotalPages(totalPages)
                .setHasNext(page<totalPages)
                .setHasPrev(page>1)
                .build();

        MetaInfo metaInfo = MetaInfo.newBuilder()
                .setServiceKey(req.getBody().getServiceKey())
                .setTableName(req.getBody().getTableName())
                .addAllColumns(allColumns)
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
