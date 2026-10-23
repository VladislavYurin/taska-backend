package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.MatchNone;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Filter Unit Tests")
class FilterTest {

    @Test
    void shouldMatchNothing_whenTopLevelConditionIsMatchNone() {
        Filter filter = new Filter(List.of(new IsNull("deleted_at"), new MatchNone("id")));

        assertTrue(filter.matchesNothing());
    }

    @Test
    void shouldMatchNothing_whenAllAlternativesOfAnyOfMatchNothing() {
        Filter filter = new Filter(List.of(new AnyOf(List.of(
                new MatchNone("id"),
                new AnyOf(List.of(new MatchNone("reporter_id"), new MatchNone("assignee_id")))
        ))));

        assertTrue(filter.matchesNothing());
    }

    @Test
    void shouldNotMatchNothing_whenSomeAlternativeOfAnyOfCanMatch() {
        Filter filter = new Filter(List.of(new AnyOf(List.of(
                new MatchNone("id"),
                new Eq("assignee_id", 1)
        ))));

        assertFalse(filter.matchesNothing());
    }
}
