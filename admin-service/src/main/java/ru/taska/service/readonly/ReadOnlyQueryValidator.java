package ru.taska.service.readonly;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.domain.DbColumnType;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReadOnlyQueryValidator {

    private static final Pattern SAFE_SQL_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final MetadataCatalogProperties properties;

    /**
     * Проверяет колонку на безопасность для SQL и существование в таблице.
     */
    void validateColumn(String column, Map<String, DbColumnType> columnTypes, String paramName) {
        checkThatStringIsSqlSafe(column);
        validateColumnExists(column, columnTypes, paramName);
    }

    private void validateColumnExists(String column, Map<String, DbColumnType> columnTypes, String paramName) {
        if (!columnTypes.containsKey(column)) {
            log.warn("Column '{}' specified in {} does not exist in table. Available columns: {}",
                    column, paramName, columnTypes.keySet());
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Column '" + column + "' does not exist in this table");
        }
    }

    /**
     * Проверяет совместимость операторов фильтра с типом колонки.
     * contains — только для текстовых колонок, from/to — для temporal и numeric.
     */
    void validateFilterOperatorCompatibility(
            Map<String, FilterOperatorsDto> filters,
            Map<String, DbColumnType> columnTypes
    ) {
        for (var entry : filters.entrySet()) {
            String columnName = entry.getKey();
            FilterOperatorsDto filter = entry.getValue();
            DbColumnType type = columnTypes.get(columnName);

            if (filter.contains() != null && !filter.contains().isBlank() && !type.supportsContains()) {
                log.warn("Operator 'contains' is not compatible with column '{}' (type: {})", columnName, type);
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Operator 'contains' is not supported for column '" + columnName
                                + "' (type: " + type + ")");
            }
            if (filter.from() != null && !filter.from().isBlank() && !type.supportsRange()) {
                log.warn("Operator 'from' is not compatible with column '{}' (type: {})", columnName, type);
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Operator 'from' is not supported for column '" + columnName
                                + "' (type: " + type + "). Only temporal and numeric columns support range filters");
            }
            if (filter.to() != null && !filter.to().isBlank() && !type.supportsRange()) {
                log.warn("Operator 'to' is not compatible with column '{}' (type: {})", columnName, type);
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Operator 'to' is not supported for column '" + columnName
                                + "' (type: " + type + "). Only temporal and numeric columns support range filters");
            }
        }
    }

    void checkThatStringIsSqlSafe(String string){
        if (!SAFE_SQL_IDENTIFIER_PATTERN.matcher(string).matches()) {
            log.warn("Unsafe string rejected: '{}'", string);
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid identifier: " + string);
        }
    }

    void validateServiceAndTable(String serviceKey, String tableName) {
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            log.warn("Service not found: {}", serviceKey);
            throw new DomainException(DomainStatus.NOT_FOUND, "Service not found: " + serviceKey);
        }

        checkThatStringIsSqlSafe(tableName);

        var tableProps = serviceProps.tables();
        List<String> allow = tableProps.allow() != null ? tableProps.allow() : Collections.emptyList();
        List<String> deny = tableProps.deny() != null ? tableProps.deny() : Collections.emptyList();
        boolean isAllowed = allow.isEmpty() || allow.contains(tableName);
        boolean isDenied = deny.contains(tableName);
        if (!isAllowed || isDenied) {
            log.warn("Access denied to table '{}' in service '{}'", tableName, serviceKey);
            throw new DomainException(DomainStatus.PERMISSION_DENIED, "Table not accessible: " + tableName);
        }
    }
}
