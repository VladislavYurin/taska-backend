package ru.taska.domain.util;

import java.util.List;

/**
 * Результат попытки получить данные из другого сервиса.
 */
public record FetchResult<T>(List<T> items, boolean available) {

    public static <T> FetchResult<T> ok(List<T> items) {
        return new FetchResult<>(items, true);
    }

    public static <T> FetchResult<T> failed() {
        return new FetchResult<>(List.of(), false);
    }
}
