package ru.taska.service.impl;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ListTableRowsResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.MetadataService;
import ru.taska.service.ReadOnlyQueryBuilder;
import ru.taska.service.SensitiveColumnMaskService;

import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    private static final String TEST_SERVICE = "auth";
    private static final String TEST_TABLE = "users";
    private static final int PAGE = 1;
    private static final int PAGE_SIZE = 20;

    @Mock
    private ReadOnlyQueryBuilder queryBuilder;

    @Mock
    private SensitiveColumnMaskService maskService;

    @Mock
    private MetadataService metadataService;

    @Mock
    private ReadOnlyRepository readOnlyRepository;

    @InjectMocks
    private AdminServiceImpl adminService;

    private ListTableRowsRequestDto requestDto;
    private ReadOnlyQueryBuilder.SqlQuery sqlQuery;
    private ReadOnlyQueryBuilder.SqlQuery countQuery;
    private List<Map<String, Object>> rows;
    private List<Map<String, Object>> maskedRows;
    private List<String> columns;

    @BeforeEach
    void setUp() {
        requestDto = new ListTableRowsRequestDto(
                TEST_SERVICE,
                TEST_TABLE,
                PAGE,
                PAGE_SIZE,
                "created_at",
                "desc",
                Map.of("status", new FilterOperatorsDto("active", null, null, null))
        );

        sqlQuery = new ReadOnlyQueryBuilder.SqlQuery("SELECT * FROM users WHERE status = $1", List.of("active"));
        countQuery = new ReadOnlyQueryBuilder.SqlQuery("SELECT COUNT(*) FROM users WHERE status = $1", List.of("active"));

        rows = List.of(
                Map.of("id", "1", "status", "active", "email", "test@example.com")
        );

        maskedRows = List.of(
                Map.of("id", "1", "status", "active", "email", "***")
        );

        columns = List.of("id", "status", "email");
    }

    @Test
    void shouldReturnSuccessResponse() {
        // given
        Mockito.when(queryBuilder.buildSafeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(sqlQuery);

        Mockito.when(queryBuilder.buildSafeCountQuery(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(countQuery);

        Mockito.when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.any(ReadOnlyQueryBuilder.SqlQuery.class)))
                .thenReturn(Flux.fromIterable(rows));

        Mockito.when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.any(ReadOnlyQueryBuilder.SqlQuery.class)))
                .thenReturn(Mono.just(1L));

        Mockito.when(maskService.maskSensitiveColumns(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(maskedRows);

        Mockito.when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columns));

        // when
        Mono<ListTableRowsResponseDto> result = adminService.listTableRows(requestDto);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    Assertions.assertThat(response.maskedRows()).hasSize(1);
                    Assertions.assertThat(response.total()).isEqualTo(1L);
                    Assertions.assertThat(response.page()).isEqualTo(PAGE);
                    Assertions.assertThat(response.pageSize()).isEqualTo(PAGE_SIZE);
                    Assertions.assertThat(response.columns()).isEqualTo(columns);
                    Assertions.assertThat(response.serviceKey()).isEqualTo(TEST_SERVICE);
                    Assertions.assertThat(response.tableName()).isEqualTo(TEST_TABLE);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyResponseWhenNoData() {
        // given
        Mockito.when(queryBuilder.buildSafeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(sqlQuery);

        Mockito.when(queryBuilder.buildSafeCountQuery(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(countQuery);

        Mockito.when(readOnlyRepository.executeQuery(Mockito.anyString(), Mockito.any(ReadOnlyQueryBuilder.SqlQuery.class)))
                .thenReturn(Flux.empty());

        Mockito.when(readOnlyRepository.countRows(Mockito.anyString(), Mockito.any(ReadOnlyQueryBuilder.SqlQuery.class)))
                .thenReturn(Mono.just(0L));

        Mockito.when(maskService.maskSensitiveColumns(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of());

        Mockito.when(metadataService.getTableColumns(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(columns));

        // when
        Mono<ListTableRowsResponseDto> result = adminService.listTableRows(requestDto);

        // then
        StepVerifier.create(result)
                .assertNext(response -> {
                    Assertions.assertThat(response.maskedRows()).isEmpty();
                    Assertions.assertThat(response.total()).isEqualTo(0L);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnErrorWhenTableNotAccessible() {
        // given
        Mockito.when(queryBuilder.buildSafeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new DomainException(DomainStatus.PERMISSION_DENIED, "Table not accessible: " + TEST_TABLE));

        // when
        Mono<ListTableRowsResponseDto> result = adminService.listTableRows(requestDto);

        // then
        StepVerifier.create(result)
                .expectError(DomainException.class)
                .verify();
    }

    @Test
    void shouldReturnErrorWhenServiceNotFound() {
        // given
        Mockito.when(queryBuilder.buildSafeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new DomainException(DomainStatus.NOT_FOUND, "Service not found: " + TEST_SERVICE));

        // when
        Mono<ListTableRowsResponseDto> result = adminService.listTableRows(requestDto);

        // then
        StepVerifier.create(result)
                .expectError(DomainException.class)
                .verify();
    }

    @Test
    void shouldReturnErrorWhenInvalidFilterColumn() {
        // given
        Mockito.when(queryBuilder.buildSafeQuery(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyMap()))
                .thenThrow(new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid filter column: status'; DROP TABLE" +
                        " users; --"));

        // when
        Mono<ListTableRowsResponseDto> result = adminService.listTableRows(requestDto);

        // then
        StepVerifier.create(result)
                .expectError(DomainException.class)
                .verify();
    }
}
