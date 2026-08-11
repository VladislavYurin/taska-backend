package ru.taska.service.readonly;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Операторы фильтрации для query params.
 */
public enum FilterOperator {

    EQUALS("equals"),
    CONTAINS("contains"),
    FROM("from"),
    TO("to");

    private final String value;

    private static final Map<String, FilterOperator> BY_VALUE = Arrays.stream(values())
            .collect(Collectors.toMap(FilterOperator::getValue, Function.identity()));

    FilterOperator(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Возвращает оператор по строковому значению.
     *
     * @throws DomainException если оператор не найден
     */
    public static FilterOperator fromValue(String value) {
        FilterOperator operator = BY_VALUE.get(value);
        if (operator == null) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Unknown filter operator: " + value);
        }
        return operator;
    }
}
