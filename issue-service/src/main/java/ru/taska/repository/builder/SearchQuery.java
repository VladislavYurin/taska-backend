package ru.taska.repository.builder;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Объект, содержащий готовый SQL-запрос и его параметры.
 */
@Getter
@Setter
public class SearchQuery {

    private String sql;
    private final Map<String, Object> params = new LinkedHashMap<>();

    public SearchQuery() {
    }

    public SearchQuery(String sql) {
        this.sql = sql;
    }

    public SearchQuery(String sql, Map<String, Object> params) {
        this.sql = sql;
        this.params.putAll(params);
    }

    /**
     * Добавляет параметр в запрос.
     *
     * @param name  имя параметра
     * @param value значение параметра
     */
    public void addParam(String name, Object value) {
        if (value != null) {
            params.put(name, value);
        }
    }

    /**
     * Добавляет все параметры из мапы.
     *
     * @param params мапа с параметрами
     */
    public void addParams(Map<String, Object> params) {
        if (params != null) {
            this.params.putAll(params);
        }
    }

    /**
     * Проверяет, есть ли параметр с указанным именем.
     */
    public boolean hasParam(String name) {
        return params.containsKey(name);
    }

    /**
     * Возвращает значение параметра по имени.
     */
    public Object getParam(String name) {
        return params.get(name);
    }

    @Override
    public String toString() {
        return "SearchQuery{" +
                "sql='" + sql + '\'' +
                ", params=" + params +
                '}';
    }
}