package ru.taska.filter;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Одно условие фильтрации: «колонка + оператор + значение». Не зависит ни от Criteria API,
 * ни от SQL — в конкретный запрос условия переводят {@link CriteriaRenderer} и {@link SqlRenderer}.
 */
public sealed interface FilterCondition {

    /** {@code column = value}. */
    record Eq(String column, Object value) implements FilterCondition {
        public Eq {
            requireValidColumn(column);
            Objects.requireNonNull(value, () -> "value must not be null for column " + column);
        }
    }

    /** {@code column IN (values)}; список значений непустой и не содержит {@code null}. */
    record In(String column, Collection<?> values) implements FilterCondition {
        public In {
            requireValidColumn(column);
            values = requireValidValues(column, values);
        }
    }

    /** {@code column NOT IN (values)}; список значений непустой и не содержит {@code null}. */
    record NotIn(String column, Collection<?> values) implements FilterCondition {
        public NotIn {
            requireValidColumn(column);
            values = requireValidValues(column, values);
        }
    }

    /** {@code column IS NULL}. */
    record IsNull(String column) implements FilterCondition {
        public IsNull {
            requireValidColumn(column);
        }
    }

    /** Поиск подстроки {@code text} в {@code column}; {@code ignoreCase} включает регистронезависимый поиск. */
    record Contains(String column, String text, boolean ignoreCase) implements FilterCondition {
        public Contains {
            requireValidColumn(column);
            Objects.requireNonNull(text, () -> "text must not be null for column " + column);
        }

        /**
         * Шаблон для LIKE: спецсимволы {@code %}, {@code _} и {@code \} в пользовательском
         * тексте экранируются, чтобы они искались буквально, а не работали как маски.
         */
        public String likePattern() {
            String escaped = text
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            return "%" + escaped + "%";
        }
    }

    /** Выполняется хотя бы одно из {@code conditions} (объединение через OR). */
    record AnyOf(List<FilterCondition> conditions) implements FilterCondition {
        public AnyOf {
            if (conditions == null || conditions.size() < 2) {
                throw new IllegalArgumentException("AnyOf requires at least two conditions");
            }
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * Условие «ни одна строка не подходит».
     * <p>
     * Зачем оно нужно. Часть фильтров работает по заранее найденному списку id. Например,
     * «задачи с меткой bug»: сначала отдельным запросом находим id задач с этой меткой, потом
     * фильтруем задачи по этим id. Если задач с меткой нет, список id пустой, и с ним нельзя
     * поступить так же, как с другими пустыми значениями:
     * <ul>
     *     <li>пропустить фильтр нельзя, иначе вернутся все задачи, хотя просили только с меткой bug;</li>
     *     <li>отрендерить {@code id IN ()} нельзя, это невалидный SQL.</li>
     * </ul>
     * Поэтому на месте такого фильтра ставится {@code MatchNone}.
     * <p>
     * Почему бы не вернуть пустой результат сразу, не строя запрос? Если фильтр стоит в
     * {@link AnyOf}, остальные ветки OR ещё могут найти строки. Например, «с меткой bug
     * <b>или</b> назначенные на меня» — это {@code FALSE OR assignee_id = :me}, то есть просто
     * мои задачи; {@link FilterBuilder#anyOf} сразу убирает такую ложную ветку из OR.
     * Если же {@code MatchNone} стоит на верхнем уровне (через AND), результат точно пустой,
     * и репозиторий может не ходить в базу, проверив {@link Filter#matchesNothing()}.
     * <p>
     * Создаётся через {@link FilterBuilder#inOrNone}.
     * <p>
     * Зачем условию «ничего не подходит» поле {@code column}? Оно нужно только
     * {@link CriteriaRenderer}. {@link SqlRenderer} пишет SQL текстом и просто вставляет
     * {@code AND FALSE}. А в Criteria API любое условие строится от колонки
     * ({@code Criteria.where("колонка")...}), и написать «просто AND FALSE» там нельзя.
     * Поэтому {@link CriteriaRenderer} берёт эту колонку и строит условие, которое никогда
     * не выполняется: {@code column IS NULL AND column IS NOT NULL}. Значение не может быть
     * одновременно пустым и непустым, так что результат тот же, что у {@code FALSE}.
     */
    record MatchNone(String column) implements FilterCondition {
        public MatchNone {
            requireValidColumn(column);
        }
    }

    private static void requireValidColumn(String column) {
        if (!SqlStringValidationUtil.isValid(column)) {
            throw new IllegalArgumentException("Invalid column name: " + column);
        }
    }

    /**
     * {@code null} внутри списка запрещён: {@code column IN (..., NULL)} не находит строки с пустой
     * колонкой, а {@code NOT IN (..., NULL)} не находит вообще ничего. Для пустых значений есть {@link IsNull}.
     */
    private static Collection<?> requireValidValues(String column, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty for column " + column);
        }
        // contains(null) не подходит: неизменяемые коллекции (List.of, Set.of) на нём бросают NPE
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("values must not contain null for column " + column);
        }
        return List.copyOf(values);
    }
}
