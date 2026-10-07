package ru.taska.domain;

import java.util.List;

public record PageResult<T>(List<T> items, long totalCount) {
    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0L);
    }
}
