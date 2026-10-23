package ru.taska.filter;

import org.springframework.data.relational.core.query.Criteria;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Contains;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.In;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.MatchNone;
import ru.taska.filter.FilterCondition.NotIn;

import java.util.List;

/**
 * Переводит {@link Filter} в {@link Criteria} для {@code R2dbcEntityTemplate}.
 * Условия объединяются через AND; пустой фильтр даёт {@link Criteria#empty()}.
 * <p>
 * Подходит для запросов к одной таблице сущности, где нужны только фильтр, сортировка
 * и пагинация (limit/offset): выборка списка, подсчёт ({@code count}), проверка наличия.
 * Строки сами превращаются в объекты сущности, SQL писать не нужно:
 * <pre>{@code
 * Query query = Query.query(CriteriaRenderer.render(filter))
 *         .sort(Sort.by("created_at"))
 *         .limit(limit)
 *         .offset(offset);
 * return r2dbcEntityTemplate.select(query, Issue.class);
 * }</pre>
 * В условиях указываются имена колонок, а не свойств сущности: тот же {@link Filter} можно
 * передать и в {@link SqlRenderer}. Если свойства с таким именем нет, Spring Data подставляет
 * строку как есть; значения при этом всё равно конвертируются по их типу (enum — в строку).
 * Если имя колонки совпало с именем свойства (однословные {@code priority}, {@code status}),
 * берётся колонка этого свойства. Поэтому свойство не должно называться так же, как чужая колонка:
 * {@code status} с {@code @Column("status_key")} при наличии колонки {@code status} перехватит фильтр по ней.
 * <p>
 * Это вариант по умолчанию. Если запрос через Criteria API не выразить (оконные функции,
 * подзапросы, группировка, ограничение числа строк в каждой группе), используйте
 * {@link SqlRenderer} и пишите SQL сами.
 */
public final class CriteriaRenderer {

    private CriteriaRenderer() {
    }

    public static Criteria render(Filter filter) {
        List<Criteria> criteriaList = filter.conditions().stream()
                .map(CriteriaRenderer::render)
                .toList();
        return Criteria.from(criteriaList);
    }

    private static Criteria render(FilterCondition condition) {
        return switch (condition) {
            case Eq c -> Criteria.where(c.column()).is(c.value());
            case In c -> Criteria.where(c.column()).in(c.values());
            case NotIn c -> Criteria.where(c.column()).notIn(c.values());
            case IsNull c -> Criteria.where(c.column()).isNull();
            case Contains c -> Criteria.where(c.column()).like(c.likePattern()).ignoreCase(c.ignoreCase());
            case AnyOf c -> anyOf(c);
            // В Criteria API нет литерала FALSE, поэтому используется заведомо ложное условие
            case MatchNone c -> Criteria.where(c.column()).isNull().and(c.column()).isNotNull();
        };
    }

    /**
     * Собирает альтернативы {@link AnyOf} в одно OR-условие в скобках: {@code (a OR b OR c)}.
     * Каждая альтернатива переводится тем же {@link #render(FilterCondition)}, поэтому внутри
     * может быть любое условие, в том числе вложенный {@link AnyOf}.
     */
    private static Criteria anyOf(AnyOf c) {
        List<FilterCondition> alternatives = c.conditions();
        Criteria anyOf = render(alternatives.getFirst());
        for (FilterCondition alternative : alternatives.subList(1, alternatives.size())) {
            anyOf = anyOf.or(render(alternative));
        }
        // Явная группа: без неё OR-цепочка при объединении с остальными условиями через AND
        // не берётся в скобки и приоритет операторов ломается (a AND x OR y)
        return Criteria.empty().and(anyOf);
    }
}
