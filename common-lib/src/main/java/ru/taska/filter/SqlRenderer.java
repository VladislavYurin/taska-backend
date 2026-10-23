package ru.taska.filter;

import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Contains;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.In;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.MatchNone;
import ru.taska.filter.FilterCondition.NotIn;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Переводит {@link Filter} в SQL-фрагмент для WHERE с именованными параметрами — для запросов
 * через {@code DatabaseClient}, которые нельзя выразить через Criteria API (например, с оконными
 * функциями). Условия объединяются через AND; пустой фильтр даёт {@code TRUE}.
 * <p>
 * Метки параметров называются {@code filter_0}, {@code filter_1}, ..., а не по имени колонки.
 * Так они не совпадут с параметрами, которые репозиторий добавляет в запрос сам
 * ({@code :limit} и т.п.), и не совпадут между собой, если одна колонка встречается
 * в фильтре дважды. Нумерация своя у каждого вызова {@code render}: если в одном запросе
 * два фильтра (подзапрос и внешний запрос, два CTE), второму нужен свой префикс,
 * см. {@link #render(Filter, String)}.
 * <p>
 * Enum-значения передаются в запрос как {@link Enum#name()}, так же как их конвертирует
 * Spring Data для {@link CriteriaRenderer}: драйвер Postgres сам Java enum кодировать не умеет.
 * <p>
 * Регистронезависимый поиск рендерится как {@code UPPER(column) LIKE UPPER(:p)}, а не {@code ILIKE},
 * чтобы SQL совпадал с тем, что строит {@link CriteriaRenderer}.
 * <p>
 * Колонки подставляются без префикса таблицы, алиасы ({@code i.deleted_at}) не поддерживаются.
 * В запросах с JOIN фильтруйте только по колонкам, имя которых не повторяется в других таблицах
 * запроса, иначе Postgres выдаст ошибку {@code column reference is ambiguous}. Для оконных функций
 * фильтр ставится во внутренний запрос к одной таблице, там префикс не нужен.
 */
public final class SqlRenderer {

    private static final String DEFAULT_PARAM_PREFIX = "filter_";

    private SqlRenderer() {
    }

    public static SqlWhere render(Filter filter) {
        return render(filter, DEFAULT_PARAM_PREFIX);
    }

    /**
     * То же, что {@link #render(Filter)}, но метки параметров начинаются с {@code paramPrefix}
     * ({@code inner_0}, {@code inner_1}, ...). Нужен, когда в одном запросе несколько фильтров.
     */
    public static SqlWhere render(Filter filter, String paramPrefix) {
        if (!SqlStringValidationUtil.isValid(paramPrefix)) {
            throw new IllegalArgumentException("Invalid param prefix: " + paramPrefix);
        }
        if (filter.isEmpty()) {
            return new SqlWhere("TRUE", Map.of());
        }

        Params params = new Params(paramPrefix);

        String sql = filter.conditions().stream()
                .map(condition -> render(condition, params))
                .collect(Collectors.joining(" AND "));

        return new SqlWhere(sql, params.values);
    }

    private static String render(FilterCondition condition, Params params) {
        return switch (condition) {
            case Eq c -> c.column() + " = :" + params.bind(c.value());
            case In c -> c.column() + " IN (:" + params.bind(c.values()) + ")";
            case NotIn c -> c.column() + " NOT IN (:" + params.bind(c.values()) + ")";
            case IsNull c -> c.column() + " IS NULL";
            case Contains c -> c.ignoreCase()
                    ? "UPPER(" + c.column() + ") LIKE UPPER(:" + params.bind(c.likePattern()) + ")"
                    : c.column() + " LIKE :" + params.bind(c.likePattern());
            case AnyOf c -> c.conditions().stream()
                    .map(alternative -> render(alternative, params))
                    .collect(Collectors.joining(" OR ", "(", ")"));
            case MatchNone c -> "FALSE";
        };
    }

    private static final class Params {

        private final String prefix;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Params(String prefix) {
            this.prefix = prefix;
        }

        private String bind(Object value) {
            String name = prefix + values.size();
            values.put(name, toDbValue(value));
            return name;
        }

        private static Object toDbValue(Object value) {
            return switch (value) {
                case Enum<?> e -> e.name();
                case Collection<?> c -> c.stream().map(Params::toDbValue).toList();
                default -> value;
            };
        }
    }
}
