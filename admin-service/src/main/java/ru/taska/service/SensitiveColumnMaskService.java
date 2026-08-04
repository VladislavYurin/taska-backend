package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Сервис для
 * Маскировки sensitive колонок
 *
 * Типы sensitive данных:
 * Пароли, хеши, токены, секреты → ("***")
 */
@Service
@RequiredArgsConstructor
public class SensitiveColumnMaskService {

    private final MetadataCatalogProperties properties;

    /**
     * Маскирует sensitive колонки в строках.
     *
     * @param rows строки из БД в виде [columnName, value]
     * @param serviceKey ключ сервиса
     * @param tableName имя таблицы
     * @return List<Row> - строки с замаскированными sensitive колонками
     */
    public List<Map<String, Object>> maskSensitiveColumns(
            List<Map<String, Object>> rows,
            String serviceKey,
            String tableName
    ) {
        // 1. Получаем sensitive колонки из конфига
        var sensitiveColumns = getSensitiveColumns(serviceKey, tableName);

        // 2. Маскируем каждую чувствительную строку
        return rows.stream()
                .map(row -> {
                    Map<String, Object> maskedRow = new HashMap<>();
                    for (var entry : row.entrySet()) {
                        String columnName = entry.getKey();
                        Object value = entry.getValue();

                        // Если колонка sensitive -> маскируем
                        if (sensitiveColumns.contains(columnName)) {
                            value = maskValue();
                        }

                        maskedRow.put(columnName, value);
                    }

                    return maskedRow;
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
    private Object maskValue() {
        /// null значения тоже маскируются
        return "***";
    }
}
