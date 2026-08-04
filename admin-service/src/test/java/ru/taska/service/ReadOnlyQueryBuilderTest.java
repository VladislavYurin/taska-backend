package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

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
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final String INJECTION_SORT = "id; DROP TABLE users; --";
    private static final String INJECTION_FILTER = "'; DROP TABLE users; --";
    private static final String INJECTION_COLUMN = "status'; DROP TABLE users; --";
    private static final String INJECTION_TABLE = "users; DROP TABLE users; --";


    private MetadataCatalogProperties properties;

    @Mock
    private MetadataCatalogProperties.TableProperties tableProps;

    @InjectMocks
    private ReadOnlyQueryBuilder queryBuilder;

    // ==================== SETUP ====================

    @BeforeEach
    void setUp() {
        properties = new MetadataCatalogProperties(
                Map.of(TEST_SERVICE, new MetadataCatalogProperties.ServiceProperties(
                        "test-alias",
                        "taska",
                        tableProps
                ))
        );

        ReflectionTestUtils.setField(queryBuilder, "properties", properties);

    }

    // ==================== 1. SQL INJECTION ТЕСТЫ ====================

    @Test
    void shouldPreventSqlInjectionInSort() {
        // Мокаем allow для этого теста
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                INJECTION_SORT, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid sort column");
    }

    @Test
    void shouldPreventSqlInjectionInSortWithUnion() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                "id UNION SELECT * FROM passwords", "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid sort column");
    }

    @Test
    void shouldPreventSqlInjectionInFilters() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto(INJECTION_FILTER, null, null, null)
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).doesNotContain("DROP TABLE");
        Assertions.assertThat(sqlQuery.params()).contains(INJECTION_FILTER);
    }

    @Test
    void shouldPreventSqlInjectionInFilterColumnName() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                INJECTION_COLUMN, new FilterOperatorsDto("active", null, null, null)
        );

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Invalid filter column");
    }

    @Test
    void shouldPreventSqlInjectionInTableName() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, INJECTION_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.PERMISSION_DENIED)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldPreventSqlInjectionInContains() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, INJECTION_FILTER, null, null)
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).doesNotContain("DROP TABLE");
        Assertions.assertThat(sqlQuery.params()).contains("%" + INJECTION_FILTER + "%");
    }

    // ==================== 2. ФИЛЬТРЫ ТЕСТЫ ====================

    @Test
    void shouldBuildQueryWithEqualsFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto("active", null, null, null)
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("\"status\" = $1");
        Assertions.assertThat(sqlQuery.params()).contains("active");
    }

    @Test
    void shouldBuildQueryWithContainsFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "email", new FilterOperatorsDto(null, "@test.com", null, null)
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("\"email\" ILIKE $1 ESCAPE '\\'");
        Assertions.assertThat(sqlQuery.params()).contains("%@test.com%");
    }

    @Test
    void shouldBuildQueryWithFromFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, "2026-01-01", null)
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("\"created_at\" >= $1::timestamptz");
        Assertions.assertThat(sqlQuery.params()).contains("2026-01-01");
    }

    @Test
    void shouldBuildQueryWithToFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of(
                "created_at", new FilterOperatorsDto(null, null, null, "2026-12-31")
        );

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("\"created_at\" <= $1::timestamptz");
        Assertions.assertThat(sqlQuery.params()).contains("2026-12-31");
    }

    @Test
    void shouldBuildQueryWithMultipleFilters() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = new LinkedHashMap<>();
        filters.put("status", new FilterOperatorsDto("active", null, null, null));
        filters.put("email", new FilterOperatorsDto(null, "@test.com", null, null));
        filters.put("created_at", new FilterOperatorsDto(null, null, "2026-01-01", "2026-12-31"));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("\"status\" = $1");
        Assertions.assertThat(sqlQuery.sql()).contains("\"email\" ILIKE $2 ESCAPE '\\'");
        Assertions.assertThat(sqlQuery.sql()).contains("\"created_at\" >= $3::timestamptz");
        Assertions.assertThat(sqlQuery.sql()).contains("\"created_at\" <= $4::timestamptz");
        Assertions.assertThat(sqlQuery.params()).contains("active", "%@test.com%", "2026-01-01", "2026-12-31");
    }

    // ==================== 3. ПАГИНАЦИЯ И СОРТИРОВКА ====================

    @Test
    void shouldBuildQueryWithPagination() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, 2, 10,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("LIMIT 10 OFFSET 10");
    }

    @Test
    void shouldBuildQueryWithSorting() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                "created_at", "desc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("ORDER BY created_at desc");
    }

    @Test
    void shouldBuildCountQuery() {
        Map<String, FilterOperatorsDto> filters = Map.of(
                "status", new FilterOperatorsDto("active", null, null, null)
        );

        ReadOnlyQueryBuilder.SqlQuery countQuery = queryBuilder.buildSafeCountQuery(
                TEST_TABLE, filters
        );

        Assertions.assertThat(countQuery.sql()).contains("SELECT COUNT(*) FROM " + TEST_TABLE);
        Assertions.assertThat(countQuery.sql()).contains("\"status\" = $1");
        Assertions.assertThat(countQuery.params()).contains("active");
    }

    // ==================== 4. ALLOWLIST ТЕСТЫ ====================

    @Test
    void shouldRejectTableNotInAllowlist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, "unknown_table", DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters)
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
                        queryBuilder.buildSafeQuery(TEST_SERVICE, BLOCKED_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.PERMISSION_DENIED)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldAllowTableInAllowlist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        Map<String, FilterOperatorsDto> filters = Map.of();

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("SELECT * FROM " + TEST_TABLE);
    }

    @Test
    void shouldAllowAnyTableWhenAllowlistEmpty() {
        Mockito.when(tableProps.allow()).thenReturn(List.of());

        Map<String, FilterOperatorsDto> filters = Map.of();

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(
                TEST_SERVICE, "any_table", DEFAULT_PAGE, DEFAULT_PAGE_SIZE,
                null, "asc", filters
        );

        Assertions.assertThat(sqlQuery.sql()).contains("SELECT * FROM any_table");
    }

    // ==================== 5. ТЕСТЫ ВАЛИДАЦИИ PAGE/PAGESIZE ====================

    @Test
    void shouldRejectPageLessThan1() {

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, 0, DEFAULT_PAGE_SIZE,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Page must be >= 1");
    }

    @Test
    void shouldRejectPageSizeLessThan1() {

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, 0,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("PageSize must be >= 1");
    }

    @Test
    void shouldRejectPageSizeGreaterThanMax() {

        Map<String, FilterOperatorsDto> filters = Map.of();

        Assertions.assertThatThrownBy(() ->
                        queryBuilder.buildSafeQuery(TEST_SERVICE, TEST_TABLE, DEFAULT_PAGE, 101,
                                null, "asc", filters)
                ).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("PageSize must be <= 100");
    }
}