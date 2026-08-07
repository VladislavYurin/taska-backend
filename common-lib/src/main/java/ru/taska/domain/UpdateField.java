package ru.taska.domain;

import java.util.function.Consumer;

public record UpdateField<T>(boolean isPresent, T value) {

    /** Поле вообще отсутствовало в запросе (клиент его не прислал) */
    public static <T> UpdateField<T> undefined() {
        return new UpdateField<>(false, null);
    }

    /** Поле было в запросе (значение может быть как конкретным T, так и null) */
    public static <T> UpdateField<T> of(T value) {
        return new UpdateField<>(true, value);
    }

    /** Выполняет действие, если поле было передано в запросе */
    public void ifPresent(Consumer<T> consumer) {
        if (isPresent) {
            consumer.accept(value);
        }
    }
}