package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Contains;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.In;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.MatchNone;
import ru.taska.filter.FilterCondition.NotIn;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FilterBuilder Unit Tests")
class FilterBuilderTest {

    @Test
    void shouldSkipOptionalFilters_whenValuesAreAbsent() {
        Filter filter = FilterBuilder.create()
                .eq("assignee_id", null)
                .eq("status_key", "   ")
                .in("priority", null)
                .in("priority", List.of())
                .notIn("status_key", List.of())
                .contains("summary", null)
                .containsIgnoreCase("summary", "")
                .anyOf(any -> any.eq("assignee_id", null))
                .build();

        assertTrue(filter.isEmpty());
        assertFalse(filter.matchesNothing());
    }

    @Test
    void shouldAddConditionsInDeclarationOrder() {
        UUID projectId = UUID.randomUUID();

        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", projectId)
                .isNull("deleted_at")
                .in("priority", List.of("HIGH"))
                .notIn("status_key", List.of("DONE"))
                .contains("issue_key", "TAS")
                .containsIgnoreCase("summary", " bug ")
                .build();

        assertEquals(List.of(
                new Eq("project_id", projectId),
                new IsNull("deleted_at"),
                new In("priority", List.of("HIGH")),
                new NotIn("status_key", List.of("DONE")),
                new Contains("issue_key", "TAS", false),
                new Contains("summary", "bug", true)
        ), filter.conditions());
    }

    @Test
    void shouldGroupAlternativesIntoAnyOf() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .containsIgnoreCase("summary", "bug")
                        .isNull("assignee_id"))
                .build();

        assertEquals(List.of(new AnyOf(List.of(
                new Contains("summary", "bug", true),
                new IsNull("assignee_id")
        ))), filter.conditions());
    }

    @Test
    void shouldSkipWholeAnyOf_whenAnyAlternativeIsSkipped() {
        UUID requestedAssignee = null;

        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("assignee_id", requestedAssignee)
                        .isNull("assignee_id"))
                .build();

        assertTrue(filter.isEmpty());
    }

    @Test
    void shouldSkipWholeAnyOf_whenAlternativeIsSkippedEvenIfOtherMatchesNothing() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .inOrNone("id", List.of())
                        .containsIgnoreCase("summary", " "))
                .build();

        assertTrue(filter.isEmpty());
        assertFalse(filter.matchesNothing());
    }

    @Test
    void shouldSkipOuterAnyOf_whenNestedAnyOfIsSkipped() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("reporter_id", 1)
                        .anyOf(inner -> inner
                                .eq("assignee_id", null)
                                .isNull("assignee_id")))
                .build();

        assertTrue(filter.isEmpty());
    }

    @Test
    void shouldKeepAnyOf_whenAlternativeIsExcludedByWhen() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("assignee_id", 1)
                        .eq("reporter_id", 1)
                        .when(false, f -> f.isNull("assignee_id")))
                .build();

        assertEquals(List.of(new AnyOf(List.of(
                new Eq("assignee_id", 1),
                new Eq("reporter_id", 1)
        ))), filter.conditions());
    }

    @Test
    void shouldUnwrapAnyOf_whenOnlyOneAlternativeIsAdded() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("assignee_id", 1)
                        .when(false, f -> f.isNull("assignee_id")))
                .build();

        assertEquals(List.of(new Eq("assignee_id", 1)), filter.conditions());
    }

    @Test
    void shouldKeepNullRowsForNotIn_whenCombinedWithIsNull() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any.notIn("status_key", List.of("DONE")).isNull("status_key"))
                .build();

        assertEquals(List.of(new AnyOf(List.of(
                new NotIn("status_key", List.of("DONE")),
                new IsNull("status_key")
        ))), filter.conditions());
    }

    @Test
    void shouldSkipNotInOrNullGroup_whenExcludedValuesAreEmpty() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any.notIn("status_key", List.of()).isNull("status_key"))
                .build();

        assertTrue(filter.isEmpty());
    }

    @Test
    void shouldFail_whenRequiredValueIsNull() {
        FilterBuilder builder = FilterBuilder.create();

        assertThrows(NullPointerException.class, () -> builder.eqRequired("project_id", null));
    }

    @Test
    void shouldFail_whenRequiredValueIsBlank() {
        FilterBuilder builder = FilterBuilder.create();

        assertThrows(IllegalArgumentException.class, () -> builder.eqRequired("project_key", "  "));
    }

    @Test
    void shouldNotStripValue_whenEq() {
        Filter filter = FilterBuilder.create()
                .eq("status_key", " OPEN ")
                .build();

        assertEquals(List.of(new Eq("status_key", " OPEN ")), filter.conditions());
    }

    @Test
    void shouldFail_whenCollectionContainsNull() {
        FilterBuilder builder = FilterBuilder.create();
        List<String> withNull = Arrays.asList("HIGH", null);

        assertThrows(IllegalArgumentException.class, () -> builder.in("priority", withNull));
        assertThrows(IllegalArgumentException.class, () -> builder.inOrNone("priority", withNull));
        assertThrows(IllegalArgumentException.class, () -> builder.notIn("priority", withNull));
    }

    @Test
    void shouldSkipInOrNone_whenValuesAreNull() {
        Filter filter = FilterBuilder.create()
                .inOrNone("id", null)
                .build();

        assertTrue(filter.isEmpty());
    }

    @Test
    void shouldMatchNothing_whenInOrNoneValuesAreEmpty() {
        Filter filter = FilterBuilder.create()
                .inOrNone("id", List.of())
                .build();

        assertEquals(List.of(new MatchNone("id")), filter.conditions());
        assertTrue(filter.matchesNothing());
    }

    @Test
    void shouldApplyBlockOnlyWhenConditionHolds() {
        Filter applied = FilterBuilder.create()
                .when(true, f -> f.isNull("deleted_at"))
                .build();
        Filter skipped = FilterBuilder.create()
                .when(false, f -> f.isNull("deleted_at"))
                .build();

        assertEquals(List.of(new IsNull("deleted_at")), applied.conditions());
        assertTrue(skipped.isEmpty());
    }

    @Test
    void shouldRejectUnsafeColumnName() {
        FilterBuilder builder = FilterBuilder.create();

        assertThrows(IllegalArgumentException.class, () -> builder.eq("id; DROP TABLE issues", 1));
    }

    @Test
    void shouldRejectTableQualifiedColumnName() {
        FilterBuilder builder = FilterBuilder.create();

        assertThrows(IllegalArgumentException.class, () -> builder.isNull("i.deleted_at"));
    }

    @Test
    void shouldSkipNotInAndSearch_whenValuesAreNullOrBlank() {
        Filter filter = FilterBuilder.create()
                .notIn("status_key", null)
                .contains("summary", "   ")
                .containsIgnoreCase("summary", "\t")
                .build();

        assertTrue(filter.isEmpty());
    }

    @Test
    void shouldKeepNestedAnyOf() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("reporter_id", 1)
                        .anyOf(inner -> inner
                                .eq("assignee_id", 1)
                                .isNull("assignee_id")))
                .build();

        assertEquals(List.of(new AnyOf(List.of(
                new Eq("reporter_id", 1),
                new AnyOf(List.of(new Eq("assignee_id", 1), new IsNull("assignee_id")))
        ))), filter.conditions());
    }

    @Test
    void shouldDropMatchNoneFromAnyOf() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .inOrNone("id", List.of())
                        .eq("assignee_id", 1)
                        .eq("reporter_id", 1))
                .build();

        assertEquals(List.of(new AnyOf(List.of(new Eq("assignee_id", 1), new Eq("reporter_id", 1)))),
                filter.conditions());
        assertFalse(filter.matchesNothing());
    }

    @Test
    void shouldUnwrapAnyOf_whenOnlyOneAlternativeIsLeftAfterDroppingMatchNone() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .inOrNone("id", List.of())
                        .eq("assignee_id", 1))
                .build();

        assertEquals(List.of(new Eq("assignee_id", 1)), filter.conditions());
    }

    @Test
    void shouldMatchNothing_whenAllAlternativesMatchNothing() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .inOrNone("id", List.of())
                        .anyOf(inner -> inner
                                .inOrNone("reporter_id", List.of())
                                .inOrNone("assignee_id", List.of())))
                .build();

        assertEquals(List.of(new MatchNone("id")), filter.conditions());
        assertTrue(filter.matchesNothing());
    }

    @Test
    void shouldNotChangeBuiltFilter_whenBuilderIsUsedAfterBuild() {
        FilterBuilder builder = FilterBuilder.create().isNull("deleted_at");
        Filter filter = builder.build();

        builder.isNull("archived_at");

        assertEquals(List.of(new IsNull("deleted_at")), filter.conditions());
    }
}
