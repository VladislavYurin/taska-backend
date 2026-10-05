package ru.taska.domain.util;

import java.util.List;

public record PageResult<T>(List<T> items, long totalCount) {
}
