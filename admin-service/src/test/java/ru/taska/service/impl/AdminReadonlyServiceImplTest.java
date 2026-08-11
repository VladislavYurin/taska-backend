package ru.taska.service.impl;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.domain.DbColumnType;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.MetadataService;
import ru.taska.service.SensitiveColumnMaskService;
import ru.taska.service.readonly.AdminReadonlyServiceImpl;
import ru.taska.service.readonly.FilterParser;
import ru.taska.service.readonly.PageableListQueries;
import ru.taska.service.readonly.ReadOnlyQueryBuilder;
import ru.taska.service.readonly.SqlQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class AdminReadonlyServiceImplTest {

    private static final String TEST_SERVICE = "auth";
    private static final String TEST_SCHEMA = "taska";
    private static final String TEST_TABLE = "users";
    private static final int PAGE = 0;
    private static final int PAGE_SIZE = 20;
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Mock
    private ReadOnlyQueryBuilder queryBuilder;

    @Mock
    private FilterParser filterParser;

    @Mock
    private SensitiveColumnMaskService maskService;

    @Mock
    private MetadataService metadataService;

    @Mock
    private ReadOnlyRepository readOnlyRepository;

    private AdminReadonlyServiceImpl adminService;

    private PageableListQueries pageableListQueries;
    private Map<String, FilterOperatorsDto> parsedFilters;
    private List<Map<String, Object>> rows;
    private List<Map<String, Object>> maskedRows;
    private Map<String, DbColumnType> columnTypes;
    private List<String> columnNames;

    @BeforeEach
    void setUp() {
        MetadataCatalogProperties.PaginationProperties pagination =
                new MetadataCatalogProperties.PaginationProperties(DEFAULT_PAGE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        MetadataCatalogProperties catalogProperties =
                new MetadataCatalogProperties(pagination, Map.of(
                        TEST_SERVICE, new MetadataCatalogProperties.ServiceProperties(
                                TEST_SERVICE, TEST_SCHEMA, new MetadataCatalogProperties.TableProperties(List.of(), List.of(), List.of()))
                ));
        adminService = new AdminReadonlyServiceImpl(
                catalogProperties, maskService, metadataService,
                readOnlyRepository, queryBuilder, filterParser
        );

        parsedFilters = Map.of("status", new FilterOperatorsDto("active", null, null, null));

        Mockito.lenient().when(filterParser.parse(Mockito.anyMap())).thenReturn(parsedFilters);
        Mockito.lenient().when(metadataService.getPrimaryKeyColumn(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just("id"));

        pageableListQueries = new PageableListQueries(
                new SqlQuery("SELECT * FROM users WHERE status = $1", List.of("active")),
                new SqlQuery("SELECT COUNT(*) FROM users WHERE status = $1", List.of("active"))
        );

        rows = List.of(
                Map.of("id", "1", "status", "active", "email", "test@example.com")
        );

        maskedRows = List.of(
                Map.of("id", "1", "status", "active", "email", "***")
        );

        columnTypes = new LinkedHashMap<>();
        columnTypes.put("id", DbColumnType.OTHER);
        columnTypes.put("status", DbColumnType.TEXT);
        columnTypes.put("email", DbColumnType.TEXT);
        columnTypes.put("created_at", DbColumnType.TEMPORAL);

        columnNames = List.of("id", "status", "email", "created_at");
    }

    @Test
    void shouldReturnSuccessResponse() {
        stubListSuccessPath();
        ListTableRowsRequestDto requestDto = listRequest(PAGE, PAGE_SIZE);

        StepVerifier.create(adminService.listTableRows(requestDto))
                .assertNext(response -> {
                    Assertions.assertThat(response.maskedRows()).hasSize(1);
                    Assertions.assertThat(response.total()).isEqualTo(1L);
                    Assertions.assertThat(response.page()).isEqualTo(PAGE);
                    Assertions.assertThat(response.pageSize()).isEqualTo(PAGE_SIZE);
                    Assertions.assertThat(response.columns()).isEqualTo(columnNames);
                    Assertions.assertThat(response.serviceKey()).isEqualTo(TEST_SERVICE);
                    Assertions.assertThat(response.tableName()).isEqualTo(TEST_TABLE);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyResponseWhenNoData() {
        Mockito.when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        Mockito.when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenReturn(pageableListQueries);
        Mockito.when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Flux.empty());
        Mockito.when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Mono.just(0L));
        Mockito.when(maskService.maskSensitiveColumns(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of());

        StepVerifier.create(adminService.listTableRows(listRequest(PAGE, PAGE_SIZE)))
                .assertNext(response -> {
                    Assertions.assertThat(response.maskedRows()).isEmpty();
                    Assertions.assertThat(response.total()).isEqualTo(0L);
                })
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorFromQueryBuilder() {
        Mockito.when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        Mockito.when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenThrow(new DomainException(DomainStatus.PERMISSION_DENIED, "Table not accessible: " + TEST_TABLE));

        StepVerifier.create(adminService.listTableRows(listRequest(PAGE, PAGE_SIZE)))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.PERMISSION_DENIED)
                .verify();
    }

    @Test
    void shouldNormalizeNegativePageToDefault() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(-1, PAGE_SIZE);

        StepVerifier.create(adminService.listTableRows(request))
                .assertNext(response -> Assertions.assertThat(response.page()).isEqualTo(DEFAULT_PAGE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(DEFAULT_PAGE, PAGE_SIZE);
    }

    @Test
    void shouldNormalizePageSizeLessThan1ToDefault() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, 0);

        StepVerifier.create(adminService.listTableRows(request))
                .assertNext(response -> Assertions.assertThat(response.pageSize()).isEqualTo(DEFAULT_PAGE_SIZE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(PAGE, DEFAULT_PAGE_SIZE);
    }

    @Test
    void shouldNormalizePageSizeGreaterThanMaxToMax() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, MAX_PAGE_SIZE + 1);

        StepVerifier.create(adminService.listTableRows(request))
                .assertNext(response -> Assertions.assertThat(response.pageSize()).isEqualTo(MAX_PAGE_SIZE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(PAGE, MAX_PAGE_SIZE);
    }

    @Test
    void shouldUseDefaultPageWhenPageIsNull() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(null, PAGE_SIZE);

        StepVerifier.create(adminService.listTableRows(request))
                .assertNext(response -> Assertions.assertThat(response.page()).isEqualTo(DEFAULT_PAGE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(DEFAULT_PAGE, PAGE_SIZE);
    }

    @Test
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, null);

        StepVerifier.create(adminService.listTableRows(request))
                .assertNext(response -> Assertions.assertThat(response.pageSize()).isEqualTo(DEFAULT_PAGE_SIZE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(PAGE, DEFAULT_PAGE_SIZE);
    }

    @Test
    void getTableRowById_shouldReturnRow() {
        String testId = "550e8400-e29b-41d4-a716-446655440000";
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(TEST_SERVICE, TEST_TABLE, testId);

        SqlQuery byIdQuery = new SqlQuery(
                "SELECT * FROM users WHERE \"id\" = $1", List.of(testId)
        );

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", testId);
        row.put("status", "active");
        row.put("email", "test@example.com");

        Map<String, Object> maskedRow = new LinkedHashMap<>();
        maskedRow.put("id", testId);
        maskedRow.put("status", "active");
        maskedRow.put("email", "***");

        Mockito.when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.just("id"));
        Mockito.when(queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_SCHEMA, TEST_TABLE, "id", testId))
                .thenReturn(byIdQuery);
        Mockito.when(readOnlyRepository.executeQuery(TEST_SERVICE, byIdQuery.parameterizedQuery(), byIdQuery.params()))
                .thenReturn(Flux.just(row));
        Mockito.when(maskService.maskSensitiveColumns(List.of(row), TEST_SERVICE, TEST_TABLE))
                .thenReturn(List.of(maskedRow));

        StepVerifier.create(adminService.getTableRowById(request))
                .assertNext(response -> {
                    Assertions.assertThat(response.row()).containsEntry("id", testId);
                    Assertions.assertThat(response.row()).containsEntry("email", "***");
                })
                .verifyComplete();
    }

    @Test
    void getTableRowById_shouldReturnNotFoundWhenRowMissing() {
        String testId = "nonexistent-id";
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(TEST_SERVICE, TEST_TABLE, testId);

        SqlQuery byIdQuery = new SqlQuery(
                "SELECT * FROM users WHERE \"id\" = $1", List.of(testId)
        );

        Mockito.when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.just("id"));
        Mockito.when(queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_SCHEMA, TEST_TABLE, "id", testId))
                .thenReturn(byIdQuery);
        Mockito.when(readOnlyRepository.executeQuery(TEST_SERVICE, byIdQuery.parameterizedQuery(), byIdQuery.params()))
                .thenReturn(Flux.empty());

        StepVerifier.create(adminService.getTableRowById(request))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void getTableRowById_shouldReturnErrorWhenNoPrimaryKey() {
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(TEST_SERVICE, TEST_TABLE, "some-id");

        Mockito.when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                        "No primary key found for table: " + TEST_TABLE)));

        StepVerifier.create(adminService.getTableRowById(request))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    private ListTableRowsRequestDto listRequest(Integer page, Integer pageSize) {
        return new ListTableRowsRequestDto(
                TEST_SERVICE,
                TEST_TABLE,
                page,
                pageSize,
                "created_at",
                "desc",
                Map.of("status.equals", "active")
        );
    }

    private void stubListSuccessPath() {
        Mockito.when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        Mockito.when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenReturn(pageableListQueries);
        Mockito.when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Flux.fromIterable(rows));
        Mockito.when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Mono.just(1L));
        Mockito.when(maskService.maskSensitiveColumns(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(maskedRows);
    }

    private void assertPageAndPageSizePassedToBuilder(int expectedPage, int expectedPageSize) {
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pageSizeCaptor = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(queryBuilder).buildSafePageableListQueries(
                Mockito.eq(TEST_SERVICE),
                Mockito.eq(TEST_SCHEMA),
                Mockito.eq(TEST_TABLE),
                pageCaptor.capture(),
                pageSizeCaptor.capture(),
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyMap(),
                Mockito.anyMap(),
                Mockito.anyString()
        );
        Assertions.assertThat(pageCaptor.getValue()).isEqualTo(expectedPage);
        Assertions.assertThat(pageSizeCaptor.getValue()).isEqualTo(expectedPageSize);
    }
}
