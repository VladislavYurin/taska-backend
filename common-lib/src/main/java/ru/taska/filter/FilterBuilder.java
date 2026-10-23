package ru.taska.filter;

import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Contains;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.Gte;
import ru.taska.filter.FilterCondition.In;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.Lte;
import ru.taska.filter.FilterCondition.MatchNone;
import ru.taska.filter.FilterCondition.NotIn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class FilterBuilder {

    private final List<FilterCondition> conditions = new ArrayList<>();

    /**
     * Было ли хотя бы одно условие пропущено из-за незаданного значения. Нужно только
     * для {@link #anyOf}: пропущенная альтернатива означает «без фильтра», и тогда вся группа OR
     * тоже не должна ничего сужать.
     */
    private boolean skipped;

    private FilterBuilder() {
    }

    public static FilterBuilder create() {
        return new FilterBuilder();
    }

    /**
     * {@code column = value}; пропускается, если значение не задано ({@code null} или пустая строка).
     * Строка сравнивается как есть, пробелы по краям не обрезаются.
     */
    public FilterBuilder eq(String column, Object value) {
        if (isPresent(value)) {
            conditions.add(new Eq(column, value));
        } else {
            skipped = true;
        }
        return this;
    }

    /**
     * {@code column = value} для обязательного фильтра. Незаданное значение считается ошибкой:
     * {@code null} даёт {@link NullPointerException}, пустая строка — {@link IllegalArgumentException}.
     */
    public FilterBuilder eqRequired(String column, Object value) {
        Objects.requireNonNull(value, () -> "Required filter value is null for column " + column);
        if (!isPresent(value)) {
            throw new IllegalArgumentException("Required filter value is blank for column " + column);
        }
        conditions.add(new Eq(column, value));
        return this;
    }

    /**
     * {@code column >= value} — нижняя граница диапазона включительно; пропускается, если значение
     * не задано ({@code null} или пустая строка).
     */
    public FilterBuilder gte(String column, Object value) {
        if (isPresent(value)) {
            conditions.add(new Gte(column, value));
        } else {
            skipped = true;
        }
        return this;
    }

    /**
     * {@code column <= value} — верхняя граница диапазона включительно; пропускается, если значение
     * не задано ({@code null} или пустая строка).
     */
    public FilterBuilder lte(String column, Object value) {
        if (isPresent(value)) {
            conditions.add(new Lte(column, value));
        } else {
            skipped = true;
        }
        return this;
    }

    /**
     * {@code column IN (values)}; пропускается, если коллекция {@code null} или пустая.
     * {@code null} внутри коллекции считается ошибкой ({@link IllegalArgumentException}).
     */
    public FilterBuilder in(String column, Collection<?> values) {
        if (values != null && !values.isEmpty()) {
            conditions.add(new In(column, values));
        } else {
            skipped = true;
        }
        return this;
    }

    /**
     * {@code column IN (values)}
     * Отличается от {@link #in} только тем, как понимается пустой список:
     * <ul>
     *     <li>{@link #in}: пустой список означает «пользователь ничего не выбрал», фильтр пропускается;</li>
     *     <li>{@code inOrNone}: пустой список означает «искали и ничего не нашли»,
     *     подходящих строк нет ({@link MatchNone}).</li>
     * </ul>
     * {@code null} внутри коллекции, как и в {@link #in}, считается ошибкой.
     * <p>
     * Каждый элемент передаётся в запрос отдельным параметром, поэтому метод рассчитан
     * на списки до нескольких сотен id. Если id может быть больше, лучше отфильтровать
     * подзапросом в SQL, чем выгружать id в приложение и передавать обратно.
     */
    public FilterBuilder inOrNone(String column, Collection<?> values) {
        if (values != null && values.isEmpty()) {
            conditions.add(new MatchNone(column));
            return this;
        }
        return in(column, values);
    }

    /**
     * {@code column NOT IN (values)}; пропускается, если коллекция {@code null} или пустая.
     * {@code null} внутри коллекции считается ошибкой ({@link IllegalArgumentException}).
     * <p>
     * Строки, где {@code column IS NULL}, сюда не попадают: в SQL {@code NULL NOT IN (...)}
     * не даёт TRUE. Если такие строки нужны, добавьте их явно:
     * <pre>{@code
     * .anyOf(any -> any.notIn("status_key", excluded).isNull("status_key"))
     * }</pre>
     * Если {@code excluded} пустой, пропускается вся группа, как и обычный {@code notIn}.
     */
    public FilterBuilder notIn(String column, Collection<?> values) {
        if (values != null && !values.isEmpty()) {
            conditions.add(new NotIn(column, values));
        } else {
            skipped = true;
        }
        return this;
    }

    /** {@code column IS NULL}; добавляется всегда. */
    public FilterBuilder isNull(String column) {
        conditions.add(new IsNull(column));
        return this;
    }

    /**
     * Поиск подстроки с учётом регистра; пропускается, если {@code text} равен {@code null} или пустой.
     * Пробелы по краям {@code text} обрезаются.
     */
    public FilterBuilder contains(String column, String text) {
        return contains(column, text, false);
    }

    /**
     * Поиск подстроки без учёта регистра; пропускается, если {@code text} равен {@code null} или пустой.
     * Пробелы по краям {@code text} обрезаются.
     */
    public FilterBuilder containsIgnoreCase(String column, String text) {
        return contains(column, text, true);
    }

    /**
     * Условия, добавленные внутри {@code block}, объединяются через OR, а не через AND.
     * <p>
     * Пропущенное условие означает «без фильтра» и внутри группы, и снаружи. Поэтому если
     * пропущена хотя бы одна альтернатива, пропускается вся группа: «X или что угодно» не сужает
     * выборку. Например, при {@code requestedAssignee == null} группа
     * <pre>{@code
     * .anyOf(any -> any.eq("assignee_id", requestedAssignee).isNull("assignee_id"))
     * }</pre>
     * не добавит ничего, а не превратится в {@code assignee_id IS NULL}.
     * <p>
     * Чтобы включать альтернативу по условию, используйте {@link #when}: альтернатива,
     * не добавленная через {@code when(false, ...)}, просто не попадает в OR и группу не отменяет.
     * <p>
     * Альтернативы {@link MatchNone} из OR убираются ({@code FALSE OR x} — это {@code x});
     * если других не осталось, вся группа становится {@link MatchNone}. Если в группе
     * ничего не добавлено, она пропускается.
     */
    public FilterBuilder anyOf(Consumer<FilterBuilder> block) {
        FilterBuilder alternatives = new FilterBuilder();
        block.accept(alternatives);
        if (alternatives.skipped) {
            skipped = true;
            return this;
        }
        List<FilterCondition> matching = alternatives.conditions.stream()
                .filter(condition -> !(condition instanceof MatchNone))
                .toList();
        switch (matching.size()) {
            case 0 -> {
                if (!alternatives.conditions.isEmpty()) {
                    conditions.add(alternatives.conditions.getFirst());
                }
            }
            case 1 -> conditions.add(matching.getFirst());
            default -> conditions.add(new AnyOf(matching));
        }
        return this;
    }

    /** Применяет {@code block} к данному билдеру только при выполнении {@code condition}, не разрывая цепочку вызовов. */
    public FilterBuilder when(boolean condition, Consumer<FilterBuilder> block) {
        if (condition) {
            block.accept(this);
        }
        return this;
    }

    public Filter build() {
        return new Filter(conditions);
    }

    private FilterBuilder contains(String column, String text, boolean ignoreCase) {
        if (isPresent(text)) {
            conditions.add(new Contains(column, text.strip(), ignoreCase));
        } else {
            skipped = true;
        }
        return this;
    }

    private static boolean isPresent(Object value) {
        return value != null && !(value instanceof String s && s.isBlank());
    }
}
