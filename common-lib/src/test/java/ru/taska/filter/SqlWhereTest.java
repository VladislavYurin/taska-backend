package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("SqlWhere Unit Tests")
class SqlWhereTest {

    @Test
    void shouldBindEveryParam() {
        GenericExecuteSpec spec = mock(GenericExecuteSpec.class, RETURNS_SELF);
        SqlWhere where = new SqlWhere("project_id = :filter_0 AND priority IN (:filter_1)",
                Map.of("filter_0", "p", "filter_1", List.of("HIGH")));

        where.bindTo(spec);

        verify(spec).bind("filter_0", "p");
        verify(spec).bind("filter_1", List.of("HIGH"));
    }

    @Test
    void shouldReturnSpecProducedByBind_notOriginalOne() {
        GenericExecuteSpec original = mock(GenericExecuteSpec.class);
        GenericExecuteSpec bound = mock(GenericExecuteSpec.class);
        when(original.bind("filter_0", "p")).thenReturn(bound);

        SqlWhere where = new SqlWhere("project_id = :filter_0", Map.of("filter_0", "p"));

        assertSame(bound, where.bindTo(original));
    }

    @Test
    void shouldReturnSameSpec_whenThereAreNoParams() {
        GenericExecuteSpec spec = mock(GenericExecuteSpec.class);

        assertSame(spec, new SqlWhere("TRUE", Map.of()).bindTo(spec));
        verifyNoInteractions(spec);
    }

    @Test
    void shouldBindRenderedFilterParams() {
        GenericExecuteSpec spec = mock(GenericExecuteSpec.class);
        when(spec.bind("filter_0", "%bug%")).thenReturn(spec);

        SqlRenderer.render(FilterBuilder.create().containsIgnoreCase("summary", "bug").build()).bindTo(spec);

        verify(spec).bind("filter_0", "%bug%");
    }

    @Test
    void shouldCopyParams_soLaterChangesDoNotLeakIntoWhere() {
        Map<String, Object> params = new HashMap<>(Map.of("filter_0", "p"));
        SqlWhere where = new SqlWhere("project_id = :filter_0", params);

        params.put("filter_1", "x");

        assertEquals(Map.of("filter_0", "p"), where.params());
    }
}
