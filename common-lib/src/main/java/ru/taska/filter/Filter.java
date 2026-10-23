package ru.taska.filter;

import java.util.List;

/**
 * Готовый набор условий фильтрации, объединяемых через AND. Создаётся через {@link FilterBuilder}.
 * Для использования оператора OR между условиями использовать FilterCondition AnyOf
 */
public record Filter(List<FilterCondition> conditions) {

    public Filter {
        conditions = List.copyOf(conditions);
    }

    /**
     * {@code true}, если фильтру заведомо не соответствует ни одна строка. Репозиторий может
     * вернуть пустой результат сразу, не выполняя запрос.
     */
    public boolean matchesNothing() {
        return conditions.stream().anyMatch(Filter::matchesNothing);
    }

    /** {@link FilterCondition.MatchNone} или группа OR, в которой ложны все альтернативы. */
    private static boolean matchesNothing(FilterCondition condition) {
        return switch (condition) {
            case FilterCondition.MatchNone c -> true;
            case FilterCondition.AnyOf c -> c.conditions().stream().allMatch(Filter::matchesNothing);
            default -> false;
        };
    }

    /** {@code true}, если условий нет и фильтр пропускает все строки. */
    public boolean isEmpty() {
        return conditions.isEmpty();
    }
}
