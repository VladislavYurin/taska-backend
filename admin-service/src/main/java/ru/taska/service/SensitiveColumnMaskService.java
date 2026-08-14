package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Сервис маскировки sensitive колонок.
 *
 * Поддерживаемые типы маскировки:
 * <ul>
 *   <li>{@code MASK_FULL} — значение заменяется на "***"</li>
 *   <li>{@code MASK_PARTIAL} — видны первый и последний символы, остальное заменяется на '*'</li>
 *   <li>{@code HIDE} — колонка полностью удаляется из ответа</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveColumnMaskService {

    private static final String FULLY_MASKED_VALUE = "***";

    private final MetadataCatalogProperties properties;

    /**
     * Маскирует sensitive колонки в строках.
     *
     * @param rows       строки из БД в виде [columnName, value]
     * @param serviceKey ключ сервиса
     * @param tableName  имя таблицы
     * @return строки с замаскированными/скрытыми sensitive колонками
     */
    public List<Map<String, Object>> maskSensitiveColumns(
            List<Map<String, Object>> rows,
            String serviceKey,
            String tableName,
            String requestId,
            String nodeId
    ) {
        Map<String, MaskType> sensitiveColumns = getSensitiveColumns(serviceKey, tableName);

        if (sensitiveColumns.isEmpty()) {
            return rows;
        }

        log.info("[{}][{}] Masking sensitive columns {} for table {}.{}", requestId, nodeId, sensitiveColumns.keySet(), serviceKey, tableName);

        return rows.stream()
                .map(row -> maskRow(row, sensitiveColumns))
                .collect(Collectors.toList());
    }

    private Map<String, Object> maskRow(Map<String, Object> row, Map<String, MaskType> sensitiveColumns) {
        Map<String, Object> maskedRow = new HashMap<>();
        for (var entry : row.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();

            MaskType maskType = sensitiveColumns.get(columnName);
            if (maskType == null) {
                maskedRow.put(columnName, value);
                continue;
            }

            switch (maskType) {
                case HIDE -> { /* колонка не попадает в ответ */ }
                case MASK_PARTIAL -> maskedRow.put(columnName, maskPartial(value));
                case MASK_FULL -> maskedRow.put(columnName, FULLY_MASKED_VALUE);
            }
        }
        return maskedRow;
    }

    private Object maskPartial(Object value) {
        if (value == null) {
            return FULLY_MASKED_VALUE;
        }
        String str = value.toString();
        if (str.length() <= 2) {
            return FULLY_MASKED_VALUE;
        }
        return str.charAt(0) + "*".repeat(str.length() - 2) + str.charAt(str.length() - 1);
    }

    private Map<String, MaskType> getSensitiveColumns(String serviceKey, String tableName) {
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps.tables().sensitiveColumns() == null) {
            return Collections.emptyMap();
        }

        String prefix = tableName + ".";

        Map<String, MaskType> result = new HashMap<>();
        for (var entry : serviceProps.tables().sensitiveColumns().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String columnName = entry.getKey().substring(prefix.length());
                MaskType maskType = parseMaskType(entry.getValue());
                result.put(columnName, maskType);
            }
        }
        return result;
    }

    /**
     * Конвертирует строковое значение типа маскировки в {@link MaskType}.
     * Если значение null или пустое — возвращает дефолтный тип.
     */
    private MaskType parseMaskType(String value) {
        if (value == null || value.isBlank()) {
            return properties.defaultMaskType();
        }
        return MaskType.valueOf(value);
    }
}
