package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.readonly.PageableListQueries;
import ru.taska.service.readonly.SqlQuery;
import ru.taska.service.readonly.ReadOnlyQueryBuilder;
import ru.taska.service.readonly.ReadOnlyQueryValidator;

import ru.taska.domain.DbColumnType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class ReadOnlyQueryBuilderTest {

    // ==================== ГЛОБАЛЬНЫЕ КОНСТАНТЫ ====================

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";
    private static final String ANOTHER_TABLE = "another_table";
    private static final String BLOCKED_TABLE = "blocked_table";
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final String INJECTION_SORT = "id; DROP TABLE users; --";
    private static final String INJECTION_FILTER = "'; DROP TABLE users; --";
    private static final String INJECTION_COLUMN = "status'; DROP TABLE users; --";
    private static final String INJECTION_TABLE = "users; DROP TABLE users; --";

    private static final Map<String, DbColumnType> TEST_COLUMNS = Map.of(
            "id", DbColumnType.OTHER,
            "status", DbColumnType.TEXT,
            "email", DbColumnType.TEXT,
            "created_at", DbColumnType.TEMPORAL
    );

    private MetadataCatalogProperties properties;

    @Mock
    private MetadataCatalogProperties.TableProperties tableProps;

    private ReadOnlyQueryValidator validator;

    private ReadOnlyQueryBuilder queryBuilder;

    // ==================== SETUP ====================

    @BeforeEach
    void setUp() {
        properties = new MetadataCatalogProperties(
                new MetadataCatalogProperties.PaginationProperties(0, 20, 100),
                Map.of(TEST_SERVICE, new MetadataCatalogProperties.ServiceProperties(
                        "test-alias",
                        "taska",
                        tableProps
                ))
        );

        Mockito.lenient().when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));
        Mockito.lenient().when(tableProps.deny()).thenReturn(List.of());

        validator = new ReadOnlyQueryValidator(properties);
        queryBuilder = new ReadOnlyQueryBuilder(validator);
    }

    // ==================== 1. SQL INJECTION ТЕСТЫ ====================

    @Test
    void shouldPreventSqlInjectionInSort() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                INJECTION_SORT, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT);
    }

    @Test
    void shouldPreventSqlInjectionInSortWithUnion() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                "id UNION SELECT * FROM passwords", "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT);
    }

    @Test
    void shouldPreventSqlInjectionInFilters() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto(INJECTION_FILTER, null, null, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).doesNotContain("DROP TABLE");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains(INJECTION_FILTER);
    }

    @Test
    void shouldPreventSqlInjectionInFilterColumnName() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                INJECTION_COLUMN, new FilterOperatorsDto("active", null, null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT);
    }

    @Test
    void shouldPreventSqlInjectionInTableName() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, INJECTION_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid identifier");
    }

    @Test
    void shouldPreventSqlInjectionInContains() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, INJECTION_FILTER, null, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).doesNotContain("DROP TABLE");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains("%" + INJECTION_FILTER + "%");
    }

    // ==================== 2. ФИЛЬТРЫ ТЕСТЫ ====================

    @Test
    void shouldBuildQueryWithEqualsFilter() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto("active", null, null, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("\"status\" = $1");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains("active");
    }

    @Test
    void shouldBuildQueryWithContainsFilter() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, "@test.com", null, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("\"email\" ILIKE $1 ESCAPE '\\'");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains("%@test.com%");
    }

    @Test
    void shouldBuildQueryWithFromFilter() {
        String fromValue = "2026-01-01T00:00:00Z";
        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, fromValue, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("\"created_at\" >= $1");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains(OffsetDateTime.parse(fromValue));
    }

    @Test
    void shouldBuildQueryWithToFilter() {
        String toValue = "2026-12-31T23:59:59Z";
        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, null, toValue)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("\"created_at\" <= $1");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains(OffsetDateTime.parse(toValue));
    }

    @Test
    void shouldBuildQueryWithMultipleFilters() {
        Map<String, FilterOperatorsDto> filters = new LinkedHashMap<>();
        filters.put("status", new FilterOperatorsDto("active", null, null, null));
        filters.put("email", new FilterOperatorsDto(null, "@test.com", null, null));
        String fromValue = "2026-01-01T00:00:00Z";
        String toValue = "2026-12-31T23:59:59Z";
        filters.put("created_at", new FilterOperatorsDto(null, null, fromValue, toValue));

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        String sql = pageableListQueries.selectQuery().parameterizedQuery();
        Assertions.assertThat(sql).contains("\"status\" = $");
        Assertions.assertThat(sql).contains("\"email\" ILIKE $");
        Assertions.assertThat(sql).contains("\"created_at\" >= $");
        Assertions.assertThat(sql).contains("\"created_at\" <= $");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains(
                "active", "%@test.com%", OffsetDateTime.parse(fromValue), OffsetDateTime.parse(toValue));
    }

    // ==================== 3. ПАГИНАЦИЯ И СОРТИРОВКА ====================

    @Test
    void shouldBuildQueryWithPagination() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, 2, 10,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("LIMIT 10 OFFSET 20");
    }

    @Test
    void shouldBuildQueryWithSorting() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                "created_at", "desc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("ORDER BY \"created_at\" DESC");
    }

    @Test
    void shouldBuildCountQuery() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto("active", null, null, null)
        );

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.countQuery().parameterizedQuery()).contains("SELECT COUNT(*) FROM \"" + TEST_TABLE + "\"");
        Assertions.assertThat(pageableListQueries.countQuery().parameterizedQuery()).contains("\"status\" = $1");
        Assertions.assertThat(pageableListQueries.selectQuery().params()).contains("active");
    }

    // ==================== 4. ALLOWLIST ТЕСТЫ ====================

    @Test
    void shouldRejectTableNotInAllowlist() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, "unknown_table", DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.PERMISSION_DENIED)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldRejectTableInDenylist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE, BLOCKED_TABLE));
        Mockito.when(tableProps.deny()).thenReturn(List.of(BLOCKED_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, BLOCKED_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.PERMISSION_DENIED)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldAllowTableInAllowlist() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("SELECT * FROM \"" + TEST_TABLE + "\"");
    }

    @Test
    void shouldAllowAnyTableWhenAllowlistEmpty() {
        Mockito.when(tableProps.allow()).thenReturn(List.of());

        Map<String, FilterOperatorsDto> filters = Map.of();

        PageableListQueries pageableListQueries = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, "any_table", DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(pageableListQueries.selectQuery().parameterizedQuery()).contains("SELECT * FROM \"any_table\"");
    }

    // ==================== 5. ВАЛИДАЦИЯ СУЩЕСТВОВАНИЯ КОЛОНОК ====================

    @Test
    void shouldRejectSortColumnNotInExistingColumns() {
        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                "nonexistent_column", "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("nonexistent_column");
    }

    // ==================== 6. ТЕСТЫ buildSafeGetByIdQuery ====================

    @Test
    void shouldBuildGetByIdQuery() {
        SqlQuery query = queryBuilder.buildSafeGetByIdQuery(
                TEST_SERVICE, TEST_TABLE, "id", "some-uuid"
        );

        Assertions.assertThat(query.parameterizedQuery()).isEqualTo("SELECT * FROM \"test_table\" WHERE \"id\"::text = $1");
        Assertions.assertThat(query.params()).containsExactly("some-uuid");
    }

    @Test
    void shouldRejectInvalidTableNameInGetById() {
        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, INJECTION_TABLE, "id", "some-uuid")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid identifier");
    }

    @Test
    void shouldRejectInvalidPkColumnInGetById() {
        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_TABLE, "id; DROP TABLE users", "some-uuid")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid identifier");
    }

    @Test
    void shouldRejectDeniedTableInGetById() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE, BLOCKED_TABLE));
        Mockito.when(tableProps.deny()).thenReturn(List.of(BLOCKED_TABLE));

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, BLOCKED_TABLE, "id", "some-uuid")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.PERMISSION_DENIED)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldRejectFilterColumnNotInExistingColumns() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "nonexistent", new FilterOperatorsDto("value", null, null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("nonexistent");
    }

    // ==================== 7. СОВМЕСТИМОСТЬ ОПЕРАТОРА С ТИПОМ КОЛОНКИ ====================

    @Test
    void shouldRejectContainsOnIntegerColumn() {
        Map<String, DbColumnType> columns = Map.of("age", DbColumnType.NUMERIC);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "age", new FilterOperatorsDto(null, "25", null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("contains")
                .hasMessageContaining("age");
    }

    @Test
    void shouldRejectContainsOnBooleanColumn() {
        Map<String, DbColumnType> columns = Map.of("is_active", DbColumnType.BOOLEAN);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "is_active", new FilterOperatorsDto(null, "true", null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("contains")
                .hasMessageContaining("is_active");
    }

    @Test
    void shouldRejectContainsOnTemporalColumn() {
        Map<String, DbColumnType> columns = Map.of("created_at", DbColumnType.TEMPORAL);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, "2026", null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("contains")
                .hasMessageContaining("created_at");
    }

    @Test
    void shouldRejectFromOnTextColumn() {
        Map<String, DbColumnType> columns = Map.of("name", DbColumnType.TEXT);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "name", new FilterOperatorsDto(null, null, "2026-01-01T00:00:00Z", null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("from")
                .hasMessageContaining("name");
    }

    @Test
    void shouldAllowFromToOnNumericColumn() {
        Map<String, DbColumnType> columns = Map.of("age", DbColumnType.NUMERIC);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "age", new FilterOperatorsDto(null, null, "18", "65")
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, columns, "id"
        );

        Assertions.assertThat(result.selectQuery().parameterizedQuery()).contains("\"age\" >= $");
        Assertions.assertThat(result.selectQuery().parameterizedQuery()).contains("\"age\" <= $");
        Assertions.assertThat(result.selectQuery().params()).contains(new BigDecimal("18"), new BigDecimal("65"));
    }

    @Test
    void shouldRejectInvalidNumericValueInFrom() {
        Map<String, DbColumnType> columns = Map.of("age", DbColumnType.NUMERIC);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "age", new FilterOperatorsDto(null, null, "not_a_number", null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid numeric value")
                .hasMessageContaining("age");
    }

    @Test
    void shouldRejectInvalidDateValueInFrom() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, "not_a_date", null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid date format")
                .hasMessageContaining("created_at");
    }

    @Test
    void shouldRejectFromOnBooleanColumn() {
        Map<String, DbColumnType> columns = Map.of("is_active", DbColumnType.BOOLEAN);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "is_active", new FilterOperatorsDto(null, null, "2026-01-01T00:00:00Z", null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("from")
                .hasMessageContaining("is_active");
    }

    @Test
    void shouldAllowContainsOnTextColumn() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, "@test.com", null, null)
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(result.selectQuery().parameterizedQuery()).contains("ILIKE");
    }

    @Test
    void shouldAllowFromToOnTemporalColumn() {
        String fromValue = "2026-01-01T00:00:00Z";
        String toValue = "2026-12-31T23:59:59Z";
        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, fromValue, toValue)
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(result.selectQuery().parameterizedQuery()).contains("\"created_at\" >= $");
        Assertions.assertThat(result.selectQuery().parameterizedQuery()).contains("\"created_at\" <= $");
    }

    @Test
    void shouldAllowEqualsOnAnyColumnType() {
        Map<String, DbColumnType> columns = Map.of(
                "name", DbColumnType.TEXT,
                "age", DbColumnType.NUMERIC,
                "is_active", DbColumnType.BOOLEAN,
                "created_at", DbColumnType.TEMPORAL
        );

        Map<String, String> valuesPerColumn = Map.of(
                "name", "value",
                "age", "25",
                "is_active", "true",
                "created_at", "value"
        );

        for (String column : columns.keySet()) {
            Map<String, FilterOperatorsDto> filters = Map.of(
                    column, new FilterOperatorsDto(valuesPerColumn.get(column), null, null, null)
            );

            PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                    TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                    null, "asc", filters, columns, "id"
            );

            Assertions.assertThat(result.selectQuery().parameterizedQuery())
                    .contains("\"" + column + "\" = $");
        }
    }

    @Test
    void shouldParseEqualsValueToCorrectType() {
        Map<String, DbColumnType> columns = Map.of("age", DbColumnType.NUMERIC);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "age", new FilterOperatorsDto("42", null, null, null)
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, columns, "id"
        );

        Assertions.assertThat(result.selectQuery().params()).containsExactly(new BigDecimal("42"));
    }

    @Test
    void shouldRejectInvalidNumericValueInEquals() {
        Map<String, DbColumnType> columns = Map.of("age", DbColumnType.NUMERIC);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "age", new FilterOperatorsDto("not_a_number", null, null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid numeric value")
                .hasMessageContaining("age");
    }

    @Test
    void shouldParseEqualsBooleanValue() {
        Map<String, DbColumnType> columns = Map.of("is_active", DbColumnType.BOOLEAN);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "is_active", new FilterOperatorsDto("true", null, null, null)
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, columns, "id"
        );

        Assertions.assertThat(result.selectQuery().params()).containsExactly(Boolean.TRUE);
    }

    @Test
    void shouldRejectInvalidBooleanValueInEquals() {
        Map<String, DbColumnType> columns = Map.of("is_active", DbColumnType.BOOLEAN);
        Map<String, FilterOperatorsDto> filters = Map.of(
                "is_active", new FilterOperatorsDto("yes", null, null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters, columns, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid boolean value")
                .hasMessageContaining("is_active");
    }

    // ==================== 8. DEFAULT ORDER / ORDER DIRECTION / CONTAINS ESCAPE ====================

    @Test
    void shouldOrderByPrimaryKeyAscWhenSortIsNull() {
        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", Map.of(), TEST_COLUMNS, "id"
        );

        Assertions.assertThat(result.selectQuery().parameterizedQuery())
                .contains("ORDER BY \"id\" ASC");
    }

    @Test
    void shouldDefaultToAscWhenOrderIsNullOrBlank() {
        PageableListQueries withNullOrder = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                "created_at", null, Map.of(), TEST_COLUMNS, "id"
        );
        PageableListQueries withBlankOrder = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                "created_at", "  ", Map.of(), TEST_COLUMNS, "id"
        );

        Assertions.assertThat(withNullOrder.selectQuery().parameterizedQuery())
                .contains("ORDER BY \"created_at\" ASC");
        Assertions.assertThat(withBlankOrder.selectQuery().parameterizedQuery())
                .contains("ORDER BY \"created_at\" ASC");
    }

    @Test
    void shouldRejectInvalidOrderDirection() {
        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                "created_at", "sideways", Map.of(), TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid order direction")
                .hasMessageContaining("sideways");
    }

    @Test
    void shouldEscapeSpecialCharactersInContainsFilter() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, "a%b_c\\d", null, null)
        );

        PageableListQueries result = queryBuilder.buildSafePageableListQueries(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters, TEST_COLUMNS, "id"
        );

        Assertions.assertThat(result.selectQuery().parameterizedQuery())
                .contains("ILIKE $1 ESCAPE '\\'");
        Assertions.assertThat(result.selectQuery().params())
                .containsExactly("%a\\%b\\_c\\\\d%");
    }

    @Test
    void shouldRejectUnknownService() {
        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafePageableListQueries("unknown-service", TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", Map.of(), TEST_COLUMNS, "id")
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.NOT_FOUND)
                .hasMessageContaining("Service not found");
    }

}