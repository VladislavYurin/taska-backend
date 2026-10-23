package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.r2dbc.query.BoundCondition;
import org.springframework.data.r2dbc.query.QueryMapper;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.sql.Table;
import ru.taska.filter.FilterCondition.AnyOf;
import ru.taska.filter.FilterCondition.Eq;
import ru.taska.filter.FilterCondition.MatchNone;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет SQL, в который R2DBC превращает полученный {@link Criteria}, а не структуру
 * самого объекта Criteria.
 */
@DisplayName("CriteriaRenderer Unit Tests")
class CriteriaRendererTest {

    private final R2dbcMappingContext mappingContext = new R2dbcMappingContext();

    private final QueryMapper queryMapper = new QueryMapper(
            PostgresDialect.INSTANCE,
            new MappingR2dbcConverter(mappingContext)
    );

    @Test
    void shouldRenderEmptyCriteria_whenFilterIsEmpty() {
        Criteria criteria = CriteriaRenderer.render(FilterBuilder.create().build());

        assertTrue(criteria.isEmpty());
    }

    @Test
    void shouldRenderAllConditionsJoinedWithAnd() {
        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", "p")
                .isNull("deleted_at")
                .in("priority", List.of("HIGH", "LOW"))
                .notIn("status_key", List.of("DONE"))
                .build();

        assertEquals("(issues.project_id = ?[$1]"
                + " AND (issues.deleted_at IS NULL)"
                + " AND (issues.priority IN (?[$2], ?[$3]))"
                + " AND (issues.status_key NOT IN (?[$4])))", toSql(filter));
    }

    @Test
    void shouldRenderContainsWithAndWithoutIgnoreCase() {
        Filter filter = FilterBuilder.create()
                .contains("issue_key", "TAS")
                .containsIgnoreCase("summary", "bug")
                .build();

        assertEquals("(issues.issue_key LIKE ?[$1] AND (UPPER(issues.summary) LIKE UPPER(?[$2])))",
                toSql(filter));
    }

    @Test
    void shouldKeepAnyOfGroupInParentheses() {
        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", "p")
                .anyOf(any -> any
                        .containsIgnoreCase("summary", "bug")
                        .containsIgnoreCase("description", "bug"))
                .build();

        assertEquals("(issues.project_id = ?[$1]"
                + " AND ((UPPER(issues.summary) LIKE UPPER(?[$2]) OR (UPPER(issues.description) LIKE UPPER(?[$3])))))",
                toSql(filter));
    }

    @Test
    void shouldRenderAlwaysFalseCondition_whenFilterMatchesNothing() {
        Filter filter = FilterBuilder.create()
                .inOrNone("id", List.of())
                .build();

        assertEquals("issues.id IS NULL AND issues.id IS NOT NULL", toSql(filter));
    }

    @Test
    void shouldKeepAlwaysFalseConditionSeparate_whenCombinedWithOtherConditions() {
        Filter filter = FilterBuilder.create()
                .isNull("deleted_at")
                .inOrNone("id", List.of())
                .build();

        assertEquals("(issues.deleted_at IS NULL AND (issues.id IS NULL AND issues.id IS NOT NULL))", toSql(filter));
    }

    @Test
    void shouldKeepOtherAlternatives_whenMatchNoneIsInsideAnyOf() {
        // FilterBuilder убирает MatchNone из OR сам, поэтому группа собирается вручную
        Filter filter = new Filter(List.of(
                new Eq("project_id", "p"),
                new AnyOf(List.of(new MatchNone("id"), new Eq("assignee_id", 1)))
        ));

        // AND связывает сильнее OR, поэтому ложная пара не захватывает альтернативу: (FALSE) OR assignee_id = ?
        assertEquals("(issues.project_id = ?[$1]"
                + " AND ((issues.id IS NULL AND issues.id IS NOT NULL OR (issues.assignee_id = ?[$2]))))",
                toSql(filter));
    }

    @Test
    void shouldKeepAnyOfGroupInParentheses_whenItIsNotLastCondition() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("assignee_id", 1)
                        .isNull("assignee_id"))
                .isNull("deleted_at")
                .build();

        assertEquals("((issues.assignee_id = ?[$1] OR (issues.assignee_id IS NULL))"
                + " AND (issues.deleted_at IS NULL))", toSql(filter));
    }

    @Test
    void shouldRenderNestedAnyOf() {
        Filter filter = FilterBuilder.create()
                .anyOf(any -> any
                        .eq("reporter_id", 1)
                        .anyOf(inner -> inner
                                .eq("assignee_id", 2)
                                .isNull("assignee_id")))
                .build();

        assertEquals("(issues.reporter_id = ?[$1]"
                + " OR ((issues.assignee_id = ?[$2] OR (issues.assignee_id IS NULL))))", toSql(filter));
    }

    @Test
    void shouldBindValuesInConditionOrder() {
        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", "p")
                .in("priority", List.of("HIGH", "LOW"))
                .containsIgnoreCase("summary", "50%")
                .build();

        assertEquals(List.of("p", "HIGH", "LOW", "%50\\%%"), boundValues(filter));
    }

    /**
     * Так фильтр используется в {@code R2dbcEntityTemplate}: с сущностью, у которой имена свойств
     * отличаются от имён колонок. Колонки должны остаться как есть, enum — превратиться в строку.
     */
    @Test
    void shouldKeepColumnNamesAndConvertEnums_whenEntityIsKnown() {
        Filter filter = FilterBuilder.create()
                .eqRequired("project_id", "p")
                .eq("status_key", "OPEN")
                .in("priority", List.of(Priority.HIGH, Priority.LOW))
                .build();

        BoundCondition condition = map(filter, mappingContext.getRequiredPersistentEntity(TestIssue.class));

        // project_id и status_key не совпали ни с одним свойством и подставлены как есть.
        // priority совпало с именем свойства, и колонка взята из маппинга, поэтому она в кавычках;
        // toString печатает её в ANSI-регистре, с Postgres-диалектом это "priority"
        assertEquals("(issues.project_id = ?[$1]"
                + " AND (issues.status_key = ?[$2])"
                + " AND (issues.\"PRIORITY\" IN (?[$3], ?[$4])))", condition.getCondition().toString());
        assertEquals(List.of("p", "OPEN", "HIGH", "LOW"), boundValues(condition));
    }

    private String toSql(Filter filter) {
        return map(filter).getCondition().toString();
    }

    private List<Object> boundValues(Filter filter) {
        return boundValues(map(filter));
    }

    private List<Object> boundValues(BoundCondition condition) {
        List<Object> values = new ArrayList<>();
        condition.getBindings().forEach(binding -> values.add(binding.getValue()));
        return values;
    }

    private BoundCondition map(Filter filter) {
        return map(filter, null);
    }

    private BoundCondition map(Filter filter, RelationalPersistentEntity<?> entity) {
        return queryMapper.getMappedObject(
                PostgresDialect.INSTANCE.getBindMarkersFactory().create(),
                CriteriaRenderer.render(filter),
                Table.create("issues"),
                entity
        );
    }

    private enum Priority { HIGH, LOW }

    @org.springframework.data.relational.core.mapping.Table("issues")
    private static final class TestIssue {
        @Id
        private UUID id;
        private UUID projectId;
        @Column("status_key")
        private String status;
        private Priority priority;
    }
}
