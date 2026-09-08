package ru.taska.service.readonly;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Сервис маскировки sensitive данных: колонок и JSON-полей.
 *
 * Поддерживаемые типы маскировки:
 * <ul>
 *   <li>{@code MASK_FULL} — значение заменяется на "***"</li>
 *   <li>{@code MASK_PARTIAL} — видны первый и последний символы, остальное заменяется на '*'</li>
 *   <li>{@code HIDE} — колонка/поле полностью удаляется из ответа</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveDataMaskService {

    private static final String FULLY_MASKED_VALUE = "***";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MetadataCatalogProperties properties;

    /**
     * Маскирует sensitive колонки и JSON-поля в строках таблицы.
     * Правила маскировки определяются конфигурацией {@code admin.metadata.services.{serviceKey}.tables}.
     * Если для таблицы нет sensitive-настроек, строки возвращаются без изменений.
     *
     * @param rows       строки из БД в виде Map(columnName → value)
     * @param serviceKey ключ сервиса из конфигурации
     * @param tableName  имя таблицы
     * @param requestId  идентификатор запроса (для логирования)
     * @param nodeId     идентификатор узла (для логирования)
     * @return строки с замаскированными/скрытыми sensitive данными
     */
    public List<Map<String, Object>> maskSensitiveData(
            List<Map<String, Object>> rows,
            String serviceKey,
            String tableName,
            String requestId,
            String nodeId
    ) {
        Map<String, MaskType> sensitiveColumns = getSensitiveColumns(serviceKey, tableName);
        Map<String, Map<String, MaskType>> sensitiveJsonFields = getSensitiveJsonFields(serviceKey, tableName);

        if (sensitiveColumns.isEmpty() && sensitiveJsonFields.isEmpty()) {
            return rows;
        }

        if (!sensitiveColumns.isEmpty()) {
            log.info("[{}][{}] Masking sensitive columns {} for table {}.{}",
                    requestId, nodeId, sensitiveColumns.keySet(), serviceKey, tableName);
        }
        if (!sensitiveJsonFields.isEmpty()) {
            log.info("[{}][{}] Masking sensitive JSON fields in columns {} for table {}.{}",
                    requestId, nodeId, sensitiveJsonFields.keySet(), serviceKey, tableName);
        }

        return rows.stream()
                .map(row -> maskRow(row, sensitiveColumns, sensitiveJsonFields))
                .toList();
    }

    /**
     * Маскирует одну строку: применяет маскировку к sensitive колонкам и JSON-полям.
     * Колонки с типом {@code HIDE} исключаются из результата.
     */
    private Map<String, Object> maskRow(Map<String, Object> row,
                                        Map<String, MaskType> sensitiveColumns,
                                        Map<String, Map<String, MaskType>> sensitiveJsonFields) {
        Map<String, Object> maskedRow = new HashMap<>();
        for (var entry : row.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();

            MaskType columnMaskType = sensitiveColumns.get(columnName);
            if (columnMaskType != null) {
                switch (columnMaskType) {
                    case HIDE -> { /* колонка не попадает в ответ */ }
                    case MASK_PARTIAL -> maskedRow.put(columnName, maskPartial(value));
                    case MASK_FULL -> maskedRow.put(columnName, FULLY_MASKED_VALUE);
                }
            } else {
                Map<String, MaskType> fieldNameMaskTypeMap = sensitiveJsonFields.get(columnName);
                if (fieldNameMaskTypeMap != null && value != null) {
                    maskedRow.put(columnName, getMaskedJson(columnName, value, fieldNameMaskTypeMap));
                } else {
                    maskedRow.put(columnName, value);
                }
            }
        }
        return maskedRow;
    }

    /**
     * Приводит значение колонки к типу {@link Json} и применяет маскировку к его полям.
     *
     * @throws DomainException если значение колонки не является типом {@link Json}
     */
    private Object getMaskedJson(String columnName, Object columnValue, Map<String, MaskType> fieldNameMaskTypeMap){
        String jsonString;
        boolean isR2dbcJson = columnValue instanceof Json;

        if (isR2dbcJson) {
            jsonString = ((Json) columnValue).asString();
        } else if (columnValue instanceof String str) {
            jsonString = str;
        } else {
            String message = "Cannot mask JSON fields in column '" + columnName
                    + "': expected Json or String type, but got "
                    + (columnValue == null ? "null" : columnValue.getClass().getSimpleName());
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }

        String maskedJson = maskJsonFields(jsonString, fieldNameMaskTypeMap);
        return isR2dbcJson ? Json.of(maskedJson) : maskedJson;
    }

    /**
     * Парсит JSON-строку и маскирует указанные поля согласно {@code fieldsToMask}.
     * Если строка пустая или null — возвращает её без изменений.
     *
     * @throws DomainException если строка не является валидным JSON или не является JSON-объектом
     */
    private String maskJsonFields(String jsonString, Map<String, MaskType> fieldsToMask) {
        if (jsonString == null || jsonString.isBlank() || fieldsToMask.isEmpty()) {
            return jsonString;
        }

        JsonNode rootNode;
        try {
            rootNode = OBJECT_MAPPER.readTree(jsonString);
        } catch (Exception e) {
            String message = "Cannot mask JSON fields: failed to parse JSON — " + e.getMessage();
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }

        if (!rootNode.isObject()) {
            String message = "Cannot mask JSON fields: expected JSON object, but got " + rootNode.getNodeType();
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }

        ObjectNode objectNode = (ObjectNode) rootNode;
        maskJsonFieldsRecursive(objectNode, fieldsToMask);
        return OBJECT_MAPPER.writeValueAsString(objectNode);
    }

    /**
     * Рекурсивно обходит JSON-объект и маскирует поля, имена которых совпадают с ключами {@code fieldsToMask}.
     * Вложенные объекты обрабатываются рекурсивно — маскировка применяется на всех уровнях вложенности.
     */
    private void maskJsonFieldsRecursive(ObjectNode node, Map<String, MaskType> fieldsToMask) {
        List<String> propertyNames = new ArrayList<>(node.propertyNames());

        for (String fieldName : propertyNames) {
            MaskType maskType = fieldsToMask.get(fieldName);
            if (maskType != null) {
                JsonNode fieldValue = node.get(fieldName);
                switch (maskType) {
                    case HIDE -> node.remove(fieldName);
                    case MASK_FULL -> node.put(fieldName, FULLY_MASKED_VALUE);
                    case MASK_PARTIAL -> {
                        if (fieldValue.isNull()) {
                            node.put(fieldName, FULLY_MASKED_VALUE);
                        } else {
                            node.put(fieldName, maskPartial(fieldValue.asString()));
                        }
                    }
                }
            } else {
                // Рекурсивный обход вложенных объектов, которые не были замаскированы
                JsonNode child = node.get(fieldName);
                if (child != null && child.isObject()) {
                    maskJsonFieldsRecursive((ObjectNode) child, fieldsToMask);
                }
            }
        }
    }

    /**
     * Частичная маскировка: оставляет первый и последний символы, заменяя остальные на '*'.
     * Если значение null или короче 3 символов — возвращает полную маску "***".
     */
    private String maskPartial(Object value) {
        if (value == null) {
            return FULLY_MASKED_VALUE;
        }
        String str = value.toString();
        if (str.length() <= 2) {
            return FULLY_MASKED_VALUE;
        }
        return str.charAt(0) + "*".repeat(str.length() - 2) + str.charAt(str.length() - 1);
    }

    /**
     * Извлекает из конфигурации карту sensitive колонок для указанной таблицы.
     * Ключ конфига имеет формат {@code table.column}, метод фильтрует по префиксу {@code tableName.}.
     *
     * @return Map(columnName → MaskType), или пустая карта если sensitive колонки не заданы
     */
    private Map<String, MaskType> getSensitiveColumns(String serviceKey, String tableName) {
        var tableProps = getTableProperties(serviceKey);
        if (tableProps.sensitiveColumns() == null) {
            return Collections.emptyMap();
        }

        return filterByTablePrefix(tableProps.sensitiveColumns(), tableName);
    }

    /**
     * Извлекает из конфигурации карту sensitive JSON-полей для указанной таблицы.
     * Конфиг имеет формат: table → column → (jsonField → maskType).
     *
     * @return Map(columnName → Map(jsonFieldName → MaskType)), или пустая карта если не задано
     */
    private Map<String, Map<String, MaskType>> getSensitiveJsonFields(String serviceKey, String tableName) {
        var tableProps = getTableProperties(serviceKey);
        var sensitiveJsonFields = tableProps.sensitiveJsonFields();
        if (sensitiveJsonFields == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, String>> columnsConfig = sensitiveJsonFields.get(tableName);
        if (columnsConfig == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, MaskType>> result = new HashMap<>();
        for (var columnConfig : columnsConfig.entrySet()) {
            String columnName = columnConfig.getKey();
            Map<String, MaskType> fieldMasks = new HashMap<>();
            if (columnConfig.getValue() == null) {
                String message = "Invalid metadata config: sensitiveJsonFields value is null for key '"
                        + tableName + "." + columnName + "'";
                log.warn(message);
                throw new DomainException(DomainStatus.INTERNAL, message);
            }
            for (var fieldEntry : columnConfig.getValue().entrySet()) {
                fieldMasks.put(fieldEntry.getKey(), resolveMaskType(fieldEntry.getValue()));
            }
            result.put(columnName, fieldMasks);
        }
        return result;
    }

    /**
     * Извлекает {@link MetadataCatalogProperties.TableProperties} для указанного сервиса.
     * Выбрасывает {@link DomainException} с понятным сообщением при некорректной конфигурации.
     */
    private MetadataCatalogProperties.TableProperties getTableProperties(String serviceKey) {
        if (properties.services() == null) {
            String message = "Invalid metadata config: 'services' section is not configured";
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            String message = "Invalid metadata config: service '" + serviceKey + "' is not configured";
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }
        if (serviceProps.tables() == null) {
            String message = "Invalid metadata config: 'tables' section is not configured for service '"
                    + serviceKey + "'";
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }
        return serviceProps.tables();
    }

    /**
     * Фильтрует записи по префиксу {@code tableName.} и конвертирует строковые значения в {@link MaskType}.
     *
     * @return Map(columnName → MaskType) для колонок, принадлежащих указанной таблице
     */
    private Map<String, MaskType> filterByTablePrefix(Map<String, String> entries, String tableName) {
        String prefix = tableName + ".";
        Map<String, MaskType> result = new HashMap<>();

        for (var entry : entries.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String columnName = entry.getKey().substring(prefix.length());
                result.put(columnName, resolveMaskType(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Конвертирует строковое значение типа маскировки в {@link MaskType}.
     * Если значение null или пустое — возвращает дефолтный тип.
     */
    private MaskType resolveMaskType(String value) {
        if (value == null || value.isBlank()) {
            return properties.defaultMaskType();
        }
        try {
            return MaskType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            String message = "Invalid metadata config: unknown mask type '" + value
                    + "', expected one of: " + Arrays.toString(MaskType.values());
            log.warn(message);
            throw new DomainException(DomainStatus.INTERNAL, message);
        }
    }
}
