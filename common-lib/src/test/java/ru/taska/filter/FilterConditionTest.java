package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Contains;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.In;
import ru.taska.filter.FilterCondition.IsNull;
import ru.taska.filter.FilterCondition.NotIn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("FilterCondition Unit Tests")
class FilterConditionTest {

    @Test
    void shouldRejectNullEqValue() {
        assertThrows(NullPointerException.class, () -> new Eq("project_id", null));
    }

    @Test
    void shouldRejectNullContainsText() {
        assertThrows(NullPointerException.class, () -> new Contains("summary", null, false));
    }

    @Test
    void shouldRejectEmptyValues_forInAndNotIn() {
        assertThrows(IllegalArgumentException.class, () -> new In("priority", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new In("priority", null));
        assertThrows(IllegalArgumentException.class, () -> new NotIn("status_key", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NotIn("status_key", null));
    }

    @Test
    void shouldRejectNullElement_forInAndNotIn_withColumnInMessage() {
        List<String> withNull = Arrays.asList("HIGH", null);

        IllegalArgumentException in = assertThrows(IllegalArgumentException.class,
                () -> new In("priority", withNull));
        IllegalArgumentException notIn = assertThrows(IllegalArgumentException.class,
                () -> new NotIn("status_key", withNull));

        assertEquals("values must not contain null for column priority", in.getMessage());
        assertEquals("values must not contain null for column status_key", notIn.getMessage());
    }

    @Test
    void shouldAcceptImmutableValues_whenCheckingForNull() {
        assertEquals(List.of("HIGH"), new In("priority", List.of("HIGH")).values());
        assertEquals(List.of("DONE"), new NotIn("status_key", Set.of("DONE")).values());
    }

    @Test
    void shouldCopyInValues_soLaterChangesDoNotLeakIntoCondition() {
        List<String> values = new ArrayList<>(List.of("HIGH"));
        In condition = new In("priority", values);

        values.add("LOW");

        assertEquals(List.of("HIGH"), condition.values());
    }

    @Test
    void shouldRequireAtLeastTwoAlternatives_forAnyOf() {
        assertThrows(IllegalArgumentException.class, () -> new AnyOf(null));
        assertThrows(IllegalArgumentException.class, () -> new AnyOf(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnyOf(List.of(new IsNull("assignee_id"))));
    }

    @Test
    void shouldRejectInvalidColumn_inEveryConditionType() {
        assertThrows(IllegalArgumentException.class, () -> new Eq(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new In("", List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new NotIn("1st", List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new IsNull("a-b"));
        assertThrows(IllegalArgumentException.class, () -> new Contains("a b", "x", true));
        assertThrows(IllegalArgumentException.class, () -> new FilterCondition.MatchNone("id;"));
    }

    @Test
    void shouldWrapTextInPercents_whenNoSpecialCharacters() {
        assertEquals("%bug%", new Contains("summary", "bug", false).likePattern());
    }

    @Test
    void shouldEscapeLikeWildcardsInSearchText() {
        Contains condition = new Contains("summary", "50%_off\\", true);

        assertEquals("%50\\%\\_off\\\\%", condition.likePattern());
    }
}
