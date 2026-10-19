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
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.domain.DbColumnType;
import ru.taska.domain.PageResult;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.MetadataService;
import ru.taska.service.readonly.SensitiveDataMaskService;
import ru.taska.service.readonly.AdminReadonlyServiceImpl;
import ru.taska.service.readonly.FilterParser;
import ru.taska.service.readonly.PageableListQueries;
import ru.taska.service.readonly.ReadOnlyQueryBuilder;
import ru.taska.service.readonly.SqlQuery;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


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
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();

    @Mock
    private ReadOnlyQueryBuilder queryBuilder;

    @Mock
    private FilterParser filterParser;

    @Mock
    private SensitiveDataMaskService maskService;

    @Mock
    private MetadataService metadataService;

    @Mock
    private ReadOnlyRepository readOnlyRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuditLogMapper auditLogMapper;

    private AdminReadonlyServiceImpl adminService;

    private PageableListQueries pageableListQueries;
    private Map<String, FilterOperatorsDto> parsedFilters;
    private List<Map<String, Object>> rows;
    private List<Map<String, Object>> maskedRows;
    private Map<String, DbColumnType> columnTypes;
    private List<String> columnNames;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MetadataCatalogProperties.PaginationProperties pagination =
                new MetadataCatalogProperties.PaginationProperties(DEFAULT_PAGE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        MetadataCatalogProperties catalogProperties =
                new MetadataCatalogProperties(pagination, MaskType.MASK_FULL, Map.of(
                        TEST_SERVICE, new MetadataCatalogProperties.ServiceProperties(
                                TEST_SERVICE, TEST_SCHEMA, new MetadataCatalogProperties.TableProperties(List.of(), List.of(), Map.of(), Map.of()))
                ));
        adminService = new AdminReadonlyServiceImpl(
                catalogProperties, maskService, metadataService,
                readOnlyRepository, queryBuilder, filterParser, auditLogRepository, auditLogMapper, objectMapper
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

        StepVerifier.create(adminService.listTableRows(requestDto, "test-request-id", "test-node-id"))
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
        when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyInt(), anyInt(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenReturn(pageableListQueries);
        when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Flux.empty());
        when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Mono.just(0L));
        when(maskService.maskSensitiveData(any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of());

        StepVerifier.create(adminService.listTableRows(listRequest(PAGE, PAGE_SIZE), "test-request-id", "test-node-id"))
                .assertNext(response -> {
                    Assertions.assertThat(response.maskedRows()).isEmpty();
                    Assertions.assertThat(response.total()).isEqualTo(0L);
                })
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorFromQueryBuilder() {
        when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyInt(), anyInt(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenThrow(new DomainException(DomainStatus.PERMISSION_DENIED, "Table not accessible: " + TEST_TABLE));

        StepVerifier.create(adminService.listTableRows(listRequest(PAGE, PAGE_SIZE), "test-request-id", "test-node-id"))
                .expectErrorMatches(e -> e instanceof DomainException de
                                         && de.getStatus() == DomainStatus.PERMISSION_DENIED)
                .verify();
    }

    @Test
    void shouldNormalizeNegativePageToDefault() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(-1, PAGE_SIZE);

        StepVerifier.create(adminService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> Assertions.assertThat(response.page()).isEqualTo(DEFAULT_PAGE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(DEFAULT_PAGE, PAGE_SIZE);
    }

    @Test
    void shouldNormalizePageSizeLessThan1ToDefault() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, 0);

        StepVerifier.create(adminService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> Assertions.assertThat(response.pageSize()).isEqualTo(DEFAULT_PAGE_SIZE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(PAGE, DEFAULT_PAGE_SIZE);
    }

    @Test
    void shouldNormalizePageSizeGreaterThanMaxToMax() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, MAX_PAGE_SIZE + 1);

        StepVerifier.create(adminService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> Assertions.assertThat(response.pageSize()).isEqualTo(MAX_PAGE_SIZE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(PAGE, MAX_PAGE_SIZE);
    }

    @Test
    void shouldUseDefaultPageWhenPageIsNull() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(null, PAGE_SIZE);

        StepVerifier.create(adminService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> Assertions.assertThat(response.page()).isEqualTo(DEFAULT_PAGE))
                .verifyComplete();

        assertPageAndPageSizePassedToBuilder(DEFAULT_PAGE, PAGE_SIZE);
    }

    @Test
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubListSuccessPath();
        ListTableRowsRequestDto request = listRequest(PAGE, null);

        StepVerifier.create(adminService.listTableRows(request, "test-request-id", "test-node-id"))
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

        when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.just("id"));
        when(queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_SCHEMA, TEST_TABLE, "id", testId))
                .thenReturn(byIdQuery);
        when(readOnlyRepository.executeQuery(TEST_SERVICE, byIdQuery.parameterizedQuery(), byIdQuery.params()))
                .thenReturn(Flux.just(row));
        when(maskService.maskSensitiveData(List.of(row), TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .thenReturn(List.of(maskedRow));

        StepVerifier.create(adminService.getTableRowById(request, "test-request-id", "test-node-id"))
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

        when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.just("id"));
        when(queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_SCHEMA, TEST_TABLE, "id", testId))
                .thenReturn(byIdQuery);
        when(readOnlyRepository.executeQuery(TEST_SERVICE, byIdQuery.parameterizedQuery(), byIdQuery.params()))
                .thenReturn(Flux.empty());

        StepVerifier.create(adminService.getTableRowById(request, "test-request-id", "test-node-id"))
                .expectErrorMatches(e -> e instanceof DomainException de
                                         && de.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void listTableRows_shouldCallMaskService() {
        stubListSuccessPath();

        StepVerifier.create(adminService.listTableRows(listRequest(PAGE, PAGE_SIZE), "test-request-id", "test-node-id"))
                .expectNextCount(1)
                .verifyComplete();

        verify(maskService).maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");
    }

    @Test
    void getTableRowById_shouldCallMaskService() {
        String testId = "550e8400-e29b-41d4-a716-446655440000";
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(TEST_SERVICE, TEST_TABLE, testId);

        SqlQuery byIdQuery = new SqlQuery(
                "SELECT * FROM users WHERE \"id\" = $1", List.of(testId)
        );

        Map<String, Object> row = Map.of("id", testId, "email", "test@example.com");

        when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.just("id"));
        when(queryBuilder.buildSafeGetByIdQuery(TEST_SERVICE, TEST_SCHEMA, TEST_TABLE, "id", testId))
                .thenReturn(byIdQuery);
        when(readOnlyRepository.executeQuery(TEST_SERVICE, byIdQuery.parameterizedQuery(), byIdQuery.params()))
                .thenReturn(Flux.just(row));
        when(maskService.maskSensitiveData(List.of(row), TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .thenReturn(List.of(row));

        StepVerifier.create(adminService.getTableRowById(request, "test-request-id", "test-node-id"))
                .expectNextCount(1)
                .verifyComplete();

        verify(maskService).maskSensitiveData(List.of(row), TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");
    }

    @Test
    void getTableRowById_shouldReturnErrorWhenNoPrimaryKey() {
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(TEST_SERVICE, TEST_TABLE, "some-id");

        when(metadataService.getPrimaryKeyColumn(TEST_SERVICE, TEST_TABLE))
                .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND,
                        "No primary key found for table: " + TEST_TABLE)));

        StepVerifier.create(adminService.getTableRowById(request, "test-request-id", "test-node-id"))
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
        when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columnTypes));
        when(queryBuilder.buildSafePageableListQueries(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), anyInt(), anyInt(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyString()))
                .thenReturn(pageableListQueries);
        when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Flux.fromIterable(rows));
        when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.anyString(), Mockito.anyList()))
                .thenReturn(Mono.just(1L));
        when(maskService.maskSensitiveData(any(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(maskedRows);
    }

    private void assertPageAndPageSizePassedToBuilder(int expectedPage, int expectedPageSize) {
        ArgumentCaptor<Integer> pageCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> pageSizeCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(queryBuilder).buildSafePageableListQueries(
                eq(TEST_SERVICE),
                eq(TEST_SCHEMA),
                eq(TEST_TABLE),
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

    @Test
    void shouldReturnEmptyResultWhenNoAuditEntriesFound() {
        FilterAuditDTO filter = new FilterAuditDTO(
                ACTOR_USER_ID, null, null, null, null, null, null, null
        );

        Mockito.when(auditLogRepository.findByFilter(Mockito.any(FilterAuditDTO.class), anyInt(), Mockito.anyLong()))
                .thenReturn(Flux.empty());
        Mockito.when(auditLogRepository.countByFilter(Mockito.any(FilterAuditDTO.class)))
                .thenReturn(Mono.just(0L));

        PageResult<AuditEntriesResponseDto> result = adminService.listAuditEntries(
                filter,
                0, 10
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.items()).isEmpty();
        Assertions.assertThat(result.totalCount()).isEqualTo(0L);
    }

    @Test
    void shouldListAuditEntriesWithAllFilters() {
        var auditLog = createLog();
        var expectedResponse = getAuditEntriesResponseDto(auditLog);

        Mockito.when(auditLogRepository.findByFilter(Mockito.any(FilterAuditDTO.class), anyInt(), Mockito.anyLong()))
                .thenReturn(Flux.just(auditLog));
        Mockito.when(auditLogRepository.countByFilter(Mockito.any(FilterAuditDTO.class)))
                .thenReturn(Mono.just(1L));
        Mockito.when(auditLogMapper.toResponseDto(Mockito.any(AuditLog.class)))
                .thenReturn(expectedResponse);

        PageResult<AuditEntriesResponseDto> result = adminService.listAuditEntries(
                new FilterAuditDTO(
                        auditLog.getActorUserId(),
                        auditLog.getAction(),
                        auditLog.getTargetService(),
                        auditLog.getTargetTable(),
                        auditLog.getTargetId(),
                        auditLog.getRequestId(),
                        Instant.parse("2024-01-01T00:00:00Z"),
                        Instant.parse("2024-12-31T23:59:59Z")
                ),
                0, 10
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.items()).hasSize(1);
        Assertions.assertThat(result.totalCount()).isEqualTo(1L);

        AuditEntriesResponseDto response = result.items().get(0);
        Assertions.assertThat(response.actorUserId()).isEqualTo(auditLog.getActorUserId());
        Assertions.assertThat(response.actorLogin()).isEqualTo(auditLog.getActorLogin());
        Assertions.assertThat(response.action()).isEqualTo(auditLog.getAction());
        Assertions.assertThat(response.targetService()).isEqualTo(auditLog.getTargetService());
        Assertions.assertThat(response.targetTable()).isEqualTo(auditLog.getTargetTable());
        Assertions.assertThat(response.targetId()).isEqualTo(auditLog.getTargetId());
        Assertions.assertThat(response.requestId()).isEqualTo(auditLog.getRequestId());
        Assertions.assertThat(response.createdAt()).isEqualTo(auditLog.getCreatedAt());
        Assertions.assertThat(response.reason()).isEqualTo(auditLog.getReason());
    }

    @Test
    void shouldReturnAllEntriesWhenFilterIsEmpty() {
        FilterAuditDTO emptyFilter = FilterAuditDTO.builder().build();

        AuditLog auditLog1 = createLog();
        AuditLog auditLog2 = createLog();

        when(auditLogRepository.findByFilter(any(FilterAuditDTO.class), anyInt(), anyLong()))
                .thenReturn(Flux.just(auditLog1, auditLog2));
        when(auditLogRepository.countByFilter(any(FilterAuditDTO.class)))
                .thenReturn(Mono.just(2L));

        PageResult<AuditEntriesResponseDto> result = adminService.listAuditEntries(emptyFilter, 0, 10).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.items()).hasSize(2);
        Assertions.assertThat(result.totalCount()).isEqualTo(2);
        Assertions.assertThat(result.page()).isEqualTo(0);
        Assertions.assertThat(result.pageSize()).isEqualTo(10);

        verify(auditLogRepository).findByFilter(any(FilterAuditDTO.class), eq(10), eq(0L));
        verify(auditLogRepository).countByFilter(any(FilterAuditDTO.class));
    }

    @Test
    void shouldFilterByDateRange() {
        var auditLog = createLog();

        var expectedResponse = getAuditEntriesResponseDto(auditLog);

        Mockito.when(auditLogMapper.toResponseDto(Mockito.any(AuditLog.class)))
                .thenReturn(expectedResponse);

        Mockito.when(auditLogRepository.findByFilter(
                        Mockito.any(FilterAuditDTO.class), anyInt(), Mockito.anyLong()))
                .thenReturn(Flux.just(auditLog));
        Mockito.when(auditLogRepository.countByFilter(Mockito.any(FilterAuditDTO.class)))
                .thenReturn(Mono.just(1L));

        FilterAuditDTO filter = new FilterAuditDTO(
                null, null, null, null, null, null,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T23:59:59Z"));

        PageResult<AuditEntriesResponseDto> result =
                adminService.listAuditEntries(filter, 0, 10).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.items()).hasSize(1);
        Assertions.assertThat(result.items().get(0).createdAt())
                .isAfter(Instant.parse("2024-01-01T00:00:00Z"))
                .isBefore(Instant.parse("2024-12-31T23:59:59Z"));
        Assertions.assertThat(result.totalCount()).isEqualTo(1L);
    }

    private static AuditLog createLog() {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        auditLog.setActorUserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        auditLog.setActorLogin("test-login");
        auditLog.setAction("TEST_ACTION");
        auditLog.setTargetService("test-service");
        auditLog.setTargetTable("test-table");
        auditLog.setTargetId("test-target");
        auditLog.setOldValue(new ObjectMapper().createObjectNode().put("value", "old-value"));
        auditLog.setNewValue(new ObjectMapper().createObjectNode().put("value", "new-value"));
        auditLog.setReason("test-reason");
        auditLog.setRequestId("test-request");
        auditLog.setCreatedAt(Instant.parse("2024-06-15T12:00:00Z"));
        return auditLog;
    }

    private static AuditEntriesResponseDto getAuditEntriesResponseDto(AuditLog auditLog) {
        AuditEntriesResponseDto expectedResponse = AuditEntriesResponseDto.builder()
                .actorUserId(auditLog.getActorUserId())
                .actorLogin(auditLog.getActorLogin())
                .action(auditLog.getAction())
                .targetService(auditLog.getTargetService())
                .targetTable(auditLog.getTargetTable())
                .targetId(auditLog.getTargetId())
                .oldValue(auditLog.getOldValue())
                .newValue(auditLog.getNewValue())
                .reason(auditLog.getReason())
                .requestId(auditLog.getRequestId())
                .createdAt(auditLog.getCreatedAt())
                .build();
        return expectedResponse;
    }
}

