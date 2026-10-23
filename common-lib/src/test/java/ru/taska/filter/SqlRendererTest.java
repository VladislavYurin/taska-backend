package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.MatchNone;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SqlRenderer Unit Tests")
class SqlRendererTest {

    @Test
    void shouldRenderTrue_whenFilterIsEmpty() {
        SqlWhere where = SqlRenderer.render(FilterBuilder.create().build());

        assertEquals("TRUE", where.sql());
        assertEquals(Map.of(), where.params());
    }

    @Test
    void shouldRenderAllConditionsWithParams() {
        UUID projectId = UUID.randomUUID();
        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", projectId)
                .isNull("deleted_at")
                .in("priority", List.of("HIGH", "LOW"))
                .notIn("status_key", List.of("DONE"))
                .contains("issue_key", "TAS")
                .anyOf(any -> any
                        .containsIgnoreCase("summary", "bug")
                        .isNull("assignee_id"))
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("project_id = :filter_0"
                + " AND deleted_at IS NULL"
                + " AND priority IN (:filter_1)"
                + " AND status_key NOT IN (:filter_2)"
                + " AND issue_key LIKE :filter_3"
                + " AND (UPPER(summary) LIKE UPPER(:filter_4) OR assignee_id IS NULL)", where.sql());
        assertEquals(Map.of(
                "filter_0", projectId,
                "filter_1", List.of("HIGH", "LOW"),
                "filter_2", List.of("DONE"),
                "filter_3", "%TAS%",
                "filter_4", "%bug%"
        ), where.params());
    }

    @Test
    void shouldRenderRangeBoundsWithParams() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-31T23:59:59Z");
        Filter filter = FilterBuilder.create()
                .gte("created_at", from)
                .lte("created_at", to)
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("created_at >= :filter_0 AND created_at <= :filter_1", where.sql());
        assertEquals(Map.of("filter_0", from, "filter_1", to), where.params());
    }

    @Test
    void shouldRenderFalse_whenFilterMatchesNothing() {
        Filter filter = FilterBuilder.create()
                .isNull("deleted_at")
                .inOrNone("id", List.of())
                .build();

        assertEquals("deleted_at IS NULL AND FALSE", SqlRenderer.render(filter).sql());
    }

    @Test
    void shouldKeepOtherAlternatives_whenMatchNoneIsInsideAnyOf() {
        // FilterBuilder убирает MatchNone из OR сам, поэтому группа собирается вручную
        Filter filter = new Filter(List.of(new AnyOf(List.of(
                new MatchNone("id"),
                new Eq("assignee_id", 1)
        ))));

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("(FALSE OR assignee_id = :filter_0)", where.sql());
        assertEquals(Map.of("filter_0", 1), where.params());
    }

    @Test
    void shouldRenderNestedAnyOfInOwnParentheses() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("reporter_id", 1)
                        .anyOf(inner -> inner
                                .eq("assignee_id", 2)
                                .isNull("assignee_id")))
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("(reporter_id = :filter_0 OR (assignee_id = :filter_1 OR assignee_id IS NULL))", where.sql());
        assertEquals(Map.of("filter_0", 1, "filter_1", 2), where.params());
    }

    @Test
    void shouldUseSeparateParams_whenSameColumnAppearsTwice() {
        Filter filter = FilterBuilder.create()
                .in("status_key", List.of("OPEN", "DONE"))
                .notIn("status_key", List.of("DONE"))
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("status_key IN (:filter_0) AND status_key NOT IN (:filter_1)", where.sql());
        assertEquals(Map.of(
                "filter_0", List.of("OPEN", "DONE"),
                "filter_1", List.of("DONE")
        ), where.params());
    }

    @Test
    void shouldBindEscapedLikePattern() {
        Filter filter = FilterBuilder.create()
                .contains("summary", "50%_off")
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals("summary LIKE :filter_0", where.sql());
        assertEquals(Map.of("filter_0", "%50\\%\\_off%"), where.params());
    }

    @Test
    void shouldBindEnumsByName() {
        Filter filter = FilterBuilder.create()
                .eq("priority", Priority.HIGH)
                .in("priority", List.of(Priority.HIGH, Priority.LOW))
                .notIn("priority", Set.of(Priority.LOW))
                .build();

        SqlWhere where = SqlRenderer.render(filter);

        assertEquals(Map.of(
                "filter_0", "HIGH",
                "filter_1", List.of("HIGH", "LOW"),
                "filter_2", List.of("LOW")
        ), where.params());
    }

    @Test
    void shouldUseGivenParamPrefix() {
        Filter filter = FilterBuilder.create()
                .eq("project_id", 1)
                .in("priority", List.of("HIGH"))
                .build();

        SqlWhere where = SqlRenderer.render(filter, "inner_");

        assertEquals("project_id = :inner_0 AND priority IN (:inner_1)", where.sql());
        assertEquals(Map.of("inner_0", 1, "inner_1", List.of("HIGH")), where.params());
    }

    @Test
    void shouldRejectUnsafeParamPrefix() {
        Filter filter = FilterBuilder.create().eq("project_id", 1).build();

        assertThrows(IllegalArgumentException.class, () -> SqlRenderer.render(filter, "p; DROP TABLE issues"));
        assertThrows(IllegalArgumentException.class, () -> SqlRenderer.render(filter, null));
    }

    private enum Priority { HIGH, LOW }
}
