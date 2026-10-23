package ru.taska.controller.utils;

import org.springframework.web.server.ServerWebExchange;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssueTypeDto;

/**
 * Утилиты для определения неизвестных значений enum-фильтров из query-параметров.
 */
public final class EnumFilterUtils {

    private static final String PRIORITY_PARAMETER = "priority";
    private static final String ISSUE_TYPE_PARAMETER = "issueType";

    private EnumFilterUtils() {
    }

    /**
     * Проверяет, содержит ли запрос неизвестное значение фильтра приоритета или типа задачи.
     * <p>
     * {@code null} сам по себе не считается неизвестным значением, поскольку параметр
     * может отсутствовать в запросе. Неизвестным считается параметр, который присутствует
     * в запросе, но не был преобразован в соответствующее значение enum.
     *
     * @param exchange текущий {@link ServerWebExchange}
     * @param priority значение фильтра приоритета
     * @param issueType значение фильтра типа задачи
     * @return {@code true}, если запрос содержит неизвестное значение одного из фильтров
     */
    public static boolean hasUnknownFilter(
            ServerWebExchange exchange,
            IssuePriorityDto priority,
            IssueTypeDto issueType
    ) {
        return isUnknownFilter(exchange, PRIORITY_PARAMETER, priority)
                || isUnknownFilter(exchange, ISSUE_TYPE_PARAMETER, issueType);
    }

    private static boolean isUnknownFilter(
            ServerWebExchange exchange,
            String parameterName,
            Object value
    ) {
        return value == null
                && exchange.getRequest().getQueryParams().containsKey(parameterName);
    }
}
