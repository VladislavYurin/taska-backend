package ru.taska.service.readonly;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.FilterOperatorsDto.FilterOperatorsDtoBuilder;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Парсит сырые фильтры из query params в структурированные FilterOperatorsDto.
 *
 * Входные данные (ключи в формате "column.operator"):
 *   "status.equals" → "active"
 *   "email.contains" → "@test.com"
 *   "created_at.from" → "2026-01-01T00:00:00Z"
 *   "created_at.to" → "2026-12-31T23:59:59Z"
 *
 * Выходные данные:
 *   "status" → FilterOperatorsDto(equals="active", null, null, null)
 *   "email" → FilterOperatorsDto(null, contains="@test.com", null, null)
 *   "created_at" → FilterOperatorsDto(null, null, from="2026-01-01T00:00:00Z", to="2026-12-31T23:59:59Z")
 */
@Slf4j
@Component
public class FilterParser {

    /**
     * Парсит сырую map фильтров в структурированную map с FilterOperatorsDto.
     */
    public Map<String, FilterOperatorsDto> parse(Map<String, String> rawFilters) {

        log.debug("Parsing filters: {}", rawFilters);

        if (rawFilters == null || rawFilters.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, FilterOperatorsDtoBuilder> buildersMap = new HashMap<>();

        for (var entry : rawFilters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isBlank()) {
                log.warn("Filter value is empty for key: {}", key);
                throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Filter value must not be empty for key: " + key);
            }

            String column;
            String operator;

            int dot = key.lastIndexOf('.');
            if (dot <= 0) {
                log.warn("Filter key missing operator: {}", key);
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Filter key must contain operator (e.g. 'column.equals'), got: " + key);
            }
            column = key.substring(0, dot);
            operator = key.substring(dot + 1);

            FilterOperator filterOperator = FilterOperator.fromValue(operator);

            FilterOperatorsDtoBuilder builder = buildersMap.computeIfAbsent(
                    column, k -> FilterOperatorsDto.builder());

            switch (filterOperator) {
                case EQUALS -> builder.equals(value);
                case CONTAINS -> builder.contains(value);
                case FROM -> builder.from(value);
                case TO -> builder.to(value);
            }
        }

        Map<String, FilterOperatorsDto> result = new HashMap<>();
        buildersMap.forEach((column, builder) -> result.put(column, builder.build()));
        return result;
    }
}
