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
import ru.taska.api.admin.v1.FilterOperators;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

        ListTableRowsRequest request = createRequest(builder -> builder.setSort(INJECTION_SORT));

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort column");
    }

    @Test
    void shouldPreventSqlInjectionInSortWithUnion() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequest(builder -> builder.setSort("id UNION SELECT * FROM passwords"));

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort column");
    }

    @Test
    void shouldPreventSqlInjectionInFilters() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("status", INJECTION_FILTER);

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).doesNotContain("DROP TABLE");
        Assertions.assertThat(sqlQuery.params()).contains(INJECTION_FILTER);
    }

    @Test
    void shouldPreventSqlInjectionInFilterColumnName() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter(INJECTION_COLUMN, "active");

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid filter column");
    }

    @Test
    void shouldPreventSqlInjectionInTableName() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequest(builder -> builder.setTableName(INJECTION_TABLE));

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldPreventSqlInjectionInContains() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("email",
                FilterOperators.newBuilder().setContains(INJECTION_FILTER).build());

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).doesNotContain("DROP TABLE");
        Assertions.assertThat(sqlQuery.params()).contains("%" + INJECTION_FILTER + "%");
    }

    // ==================== 2. ФИЛЬТРЫ ТЕСТЫ ====================

    @Test
    void shouldBuildQueryWithEqualsFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("status",
                FilterOperators.newBuilder().setEquals("active").build());

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("WHERE status = $1");
        Assertions.assertThat(sqlQuery.params()).contains("active");
    }

    @Test
    void shouldBuildQueryWithContainsFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("email",
                FilterOperators.newBuilder().setContains("@test.com").build());

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("WHERE email ILIKE $1");
        Assertions.assertThat(sqlQuery.params()).contains("%@test.com%");
    }

    @Test
    void shouldBuildQueryWithFromFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("created_at",
                FilterOperators.newBuilder().setFrom("2026-01-01").build());

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("WHERE created_at >= $1");
        Assertions.assertThat(sqlQuery.params()).contains("2026-01-01");
    }

    @Test
    void shouldBuildQueryWithToFilter() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequestWithFilter("created_at",
                FilterOperators.newBuilder().setTo("2026-12-31").build());

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("WHERE created_at <= $1");
        Assertions.assertThat(sqlQuery.params()).contains("2026-12-31");
    }

    @Test
    void shouldBuildQueryWithMultipleFilters() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));
        FilterOperators dateFilter = FilterOperators.newBuilder()
                .setFrom("2026-01-01")
                .setTo("2026-12-31")
                .build();

        ListTableRowsRequest request = createRequest(builder -> builder
                .putFilters("status", FilterOperators.newBuilder().setEquals("active").build())
                .putFilters("email", FilterOperators.newBuilder().setContains("@test.com").build())
                .putFilters("created_at", dateFilter));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("status = $1");
        Assertions.assertThat(sqlQuery.sql()).contains("email ILIKE $2");
        Assertions.assertThat(sqlQuery.sql()).contains("created_at >= $3");
        Assertions.assertThat(sqlQuery.sql()).contains("created_at <= $4");
        Assertions.assertThat(sqlQuery.params()).contains("active", "%@test.com%", "2026-01-01", "2026-12-31");
    }

    // ==================== 3. ПАГИНАЦИЯ И СОРТИРОВКА ====================

    @Test
    void shouldBuildQueryWithPagination() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequest(builder -> builder
                .setPage(2)
                .setPageSize(10));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("LIMIT 10 OFFSET 10");
    }

    @Test
    void shouldBuildQueryWithSorting() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));

        ListTableRowsRequest request = createRequest(builder -> builder
                .setSort("created_at")
                .setOrder("desc"));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("ORDER BY created_at desc");
    }

    @Test
    void shouldBuildCountQuery() {
        ListTableRowsRequest request = createRequestWithFilter("status",
                FilterOperators.newBuilder().setEquals("active").build());

        ReadOnlyQueryBuilder.SqlQuery countQuery = queryBuilder.buildSafeCountQuery(
                TEST_TABLE,
                request.getBody().getFiltersMap()
        );

        Assertions.assertThat(countQuery.sql()).contains("SELECT COUNT(*) FROM " + TEST_TABLE);
        Assertions.assertThat(countQuery.sql()).contains("WHERE status = $1");
        Assertions.assertThat(countQuery.params()).contains("active");
    }

    // ==================== 4. ALLOWLIST ТЕСТЫ ====================

    @Test
    void shouldRejectTableNotInAllowlist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));
        ListTableRowsRequest request = createRequest(builder -> builder.setTableName("unknown_table"));

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldRejectTableInDenylist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE, BLOCKED_TABLE));
        Mockito.when(tableProps.deny()).thenReturn(List.of(BLOCKED_TABLE));
        ListTableRowsRequest request = createRequest(builder -> builder.setTableName(BLOCKED_TABLE));

        Assertions.assertThatThrownBy(() -> queryBuilder.buildSafeQuery(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table not accessible");
    }

    @Test
    void shouldAllowTableInAllowlist() {
        Mockito.when(tableProps.allow()).thenReturn(List.of(TEST_TABLE, ANOTHER_TABLE));
        ListTableRowsRequest request = createRequest(builder -> builder.setTableName(TEST_TABLE));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("SELECT * FROM " + TEST_TABLE);
    }

    @Test
    void shouldAllowAnyTableWhenAllowlistEmpty() {
        Mockito.when(tableProps.allow()).thenReturn(List.of());
        ListTableRowsRequest request = createRequest(builder -> builder.setTableName("any_table"));

        ReadOnlyQueryBuilder.SqlQuery sqlQuery = queryBuilder.buildSafeQuery(request);

        Assertions.assertThat(sqlQuery.sql()).contains("SELECT * FROM any_table");
    }

    // ==================== HELPER METHODS ====================

    private Header buildHeader() {
        return Header.newBuilder()
                .setRequestId("test-req-id")
                .setNodeId("test-node-id")
                .build();
    }

    private ListTableRowsRequest createRequest(Consumer<ListTableRowsRequestBody.Builder> bodyConfig) {
        ListTableRowsRequestBody.Builder bodyBuilder = ListTableRowsRequestBody.newBuilder()
                .setServiceKey(TEST_SERVICE)
                .setTableName(TEST_TABLE)
                .setPage(DEFAULT_PAGE)
                .setPageSize(DEFAULT_PAGE_SIZE);

        bodyConfig.accept(bodyBuilder);

        return ListTableRowsRequest.newBuilder()
                .setHeader(buildHeader())
                .setBody(bodyBuilder.build())
                .build();
    }

    private ListTableRowsRequest createRequestWithFilter(String column, FilterOperators filter) {
        return createRequest(builder -> builder.putFilters(column, filter));
    }

    private ListTableRowsRequest createRequestWithFilter(String column, String value) {
        return createRequestWithFilter(column, FilterOperators.newBuilder().setEquals(value).build());
    }
}