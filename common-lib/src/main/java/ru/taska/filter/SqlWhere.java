package ru.taska.filter;

import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Map;

/**
 * Результат {@link SqlRenderer}: SQL-фрагмент для WHERE (без самого слова WHERE)
 * и значения его именованных параметров.
 */
public record SqlWhere(String sql, Map<String, Object> params) {

    public SqlWhere {
        params = Map.copyOf(params);
    }

    /** Привязывает параметры фильтра к запросу. */
    public DatabaseClient.GenericExecuteSpec bindTo(DatabaseClient.GenericExecuteSpec spec) {
        for (var entry : params.entrySet()) {
            spec = spec.bind(entry.getKey(), entry.getValue());
        }
        return spec;
    }
}
