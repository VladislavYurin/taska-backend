package ru.taska.domain;

import java.util.List;

/**
 * Результат постраничной выборки данных.
 *
 * @param <T>        тип элементов в списке
 * @param items      список элементов текущей страницы
 * @param totalCount общее количество элементов (без учета пагинации)
 * @param page       номер текущей страницы, 0-based
 * @param pageSize   размер страницы (количество элементов на странице)
 */
public record PageResult<T>(List<T> items, long totalCount, Integer page, Integer pageSize) {
}
