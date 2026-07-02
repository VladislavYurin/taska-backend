package ru.taska.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 *  Типы агрегатов в доменных событиях Kafka (поле aggregateType в TaskaEvent).
 */
@Getter
@RequiredArgsConstructor
public enum AggregateType {
    USER("user"),
    ISSUE("issue"),
    PROJECT("project");

    private final String value;

    public static AggregateType fromValue(String value) {
        if (value == null) {
            return null;
        }

        var normalized = value.trim();

        return Arrays.stream(AggregateType.values())
                .filter(type -> type.getValue().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }
}