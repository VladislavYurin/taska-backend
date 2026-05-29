package ru.taska.domain;

import java.util.List;

public record PageResult<T>(List<T> items, long totalCount) {
}
