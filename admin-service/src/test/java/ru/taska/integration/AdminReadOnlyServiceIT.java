package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.dto.AuditEntriesResponseDto;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.dto.GetTableRowByIdRequestDto;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for the three AdminService RPC use-cases at the service layer.
 * Pure validation / SQL-building logic is covered by unit tests — here only wiring + real Postgres.
 */
class AdminReadOnlyServiceIT extends AbstractIT {

    private static final UUID FIRST_AUDIT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SECOND_AUDIT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private AdminReadonlyService adminReadonlyService;

    @Test
    void getCatalog_includesFixtureTableFromInformationSchema() {
        StepVerifier.create(metadataService.getCatalog())
                .assertNext(catalog -> {
                    ServiceDto admin = catalog.services().stream()
                            .filter(s -> FIXTURE_SERVICE.equals(s.serviceKey()))
                            .findFirst()
                            .orElseThrow();

                    Assertions.assertThat(admin.tables())
                            .extracting(TableDto::name)
                            .contains(FIXTURE_TABLE);

                    TableDto table = admin.tables().stream()
                            .filter(t -> FIXTURE_TABLE.equals(t.name()))
                            .findFirst()
                            .orElseThrow();

                    Assertions.assertThat(table.columns())
                            .anyMatch(c -> "id".equals(c.name()) && c.primaryKey());
                    Assertions.assertThat(table.columns())
                            .anyMatch(c -> "email".equals(c.name()) && c.sensitive());
                })
                .verifyComplete();
    }

    @Test
    void listTableRows_returnsRowsTotalAndMasksSensitiveColumns() {
        ListTableRowsRequestDto request = new ListTableRowsRequestDto(
                FIXTURE_SERVICE,
                FIXTURE_TABLE,
                0,
                20,
                null,
                null,
                Map.of()
        );

        StepVerifier.create(adminReadonlyService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> {
                    Assertions.assertThat(response.total()).isEqualTo(2L);
                    Assertions.assertThat(response.maskedRows()).hasSize(2);
                    Assertions.assertThat(response.maskedRows())
                            .allSatisfy(row -> Assertions.assertThat(row.get("email")).isEqualTo("***"));
                    Assertions.assertThat(response.columns()).contains("id", "login", "email");
                })
                .verifyComplete();
    }

    @Test
    void listTableRows_filterEqualsBindsAndFiltersInPostgres() {
        ListTableRowsRequestDto request = new ListTableRowsRequestDto(
                FIXTURE_SERVICE,
                FIXTURE_TABLE,
                0,
                20,
                null,
                null,
                Map.of("login.equals", FIXTURE_USER_LOGIN)
        );

        StepVerifier.create(adminReadonlyService.listTableRows(request, "test-request-id", "test-node-id"))
                .assertNext(response -> {
                    Assertions.assertThat(response.total()).isEqualTo(1L);
                    Assertions.assertThat(response.maskedRows()).hasSize(1);
                    Assertions.assertThat(response.maskedRows().getFirst().get("login"))
                            .isEqualTo(FIXTURE_USER_LOGIN);
                    Assertions.assertThat(response.maskedRows().getFirst().get("email"))
                            .isEqualTo("***");
                })
                .verifyComplete();
    }

    @Test
    void getTableRowById_returnsMaskedRow() {
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(
                FIXTURE_SERVICE,
                FIXTURE_TABLE,
                FIXTURE_USER_ID
        );

        StepVerifier.create(adminReadonlyService.getTableRowById(request, "test-request-id", "test-node-id"))
                .assertNext(response -> {
                    Assertions.assertThat(response.row().get("id").toString()).isEqualTo(FIXTURE_USER_ID);
                    Assertions.assertThat(response.row().get("login")).isEqualTo(FIXTURE_USER_LOGIN);
                    Assertions.assertThat(response.row().get("email")).isEqualTo("***");
                })
                .verifyComplete();
    }

    @Test
    void getTableRowById_missingId_returnsNotFound() {
        GetTableRowByIdRequestDto request = new GetTableRowByIdRequestDto(
                FIXTURE_SERVICE,
                FIXTURE_TABLE,
                "00000000-0000-0000-0000-000000000000"
        );

        StepVerifier.create(adminReadonlyService.getTableRowById(request, "test-request-id", "test-node-id"))
                .expectErrorMatches(e -> e instanceof DomainException de
                                         && de.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    @Test
    void listAuditEntries_withAllFilters_returnsMatchingEntry() {
        UUID actorUserId = UUID.fromString(FIXTURE_USER_ID);

        FilterAuditDTO filterDTO = new FilterAuditDTO(
                actorUserId,
                "action",
                FIXTURE_SERVICE,
                FIXTURE_TABLE,
                "00000000-0000-0000-0000-000000000001",
                "requestId",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-31T23:59:59.999999999Z")
        );

        StepVerifier.create(adminReadonlyService.listAuditEntries(filterDTO, 0, 10))
                .assertNext(response -> {
                    Assertions.assertThat(response.totalCount()).isEqualTo(1L);
                    Assertions.assertThat(response.items()).hasSize(1);

                    AuditEntriesResponseDto entry = response.items().getFirst();

                    Assertions.assertThat(entry.id()).isEqualTo(UUID.fromString("33333333-3333-3333-3333-333333333333"));
                    Assertions.assertThat(entry.actorUserId()).isEqualTo(actorUserId);
                    Assertions.assertThat(entry.actorLogin()).isEqualTo(FIXTURE_USER_LOGIN);
                    Assertions.assertThat(entry.action()).isEqualTo("action");
                    Assertions.assertThat(entry.targetService()).isEqualTo(FIXTURE_SERVICE);
                    Assertions.assertThat(entry.targetTable()).isEqualTo(FIXTURE_TABLE);
                    Assertions.assertThat(entry.targetId()).isEqualTo("00000000-0000-0000-0000-000000000001");
                    Assertions.assertThat(entry.requestId()).isEqualTo("requestId");
                    Assertions.assertThat(entry.createdAt()).isEqualTo(Instant.parse("2024-01-15T12:00:00Z"));
                    Assertions.assertThat(entry.reason()).isEqualTo("test-reason");
                    Assertions.assertThat(entry.oldValue().get("name").asText()).isEqualTo("old-name");
                    Assertions.assertThat(entry.newValue().get("name").asText()).isEqualTo("new-name");
                })
                .verifyComplete();
    }

    @Test
    void listAuditEntries_withoutFilters_returnsNewestFirstWithPagination() {
        StepVerifier.create(adminReadonlyService.listAuditEntries(null, 0, 1))
                .assertNext(firstPage -> {
                    Assertions.assertThat(firstPage.totalCount()).isEqualTo(2L);
                    Assertions.assertThat(firstPage.items()).hasSize(1);
                    Assertions.assertThat(firstPage.page()).isEqualTo(0);
                    Assertions.assertThat(firstPage.pageSize()).isEqualTo(1);
                    Assertions.assertThat(firstPage.items().getFirst().createdAt())
                            .isEqualTo(Instant.parse("2024-02-01T09:30:00Z"));
                })
                .verifyComplete();

        StepVerifier.create(adminReadonlyService.listAuditEntries(FilterAuditDTO.builder().build(), 1, 1))
                .assertNext(secondPage -> {
                    Assertions.assertThat(secondPage.totalCount()).isEqualTo(2L);
                    Assertions.assertThat(secondPage.items()).hasSize(1);
                    Assertions.assertThat(secondPage.items().getFirst().createdAt())
                            .isEqualTo(Instant.parse("2024-01-15T12:00:00Z"));
                })
                .verifyComplete();
    }

    @Test
    void listAuditEntries_byRequestId_findsOnlyThatRequest() {
        FilterAuditDTO filter = FilterAuditDTO.builder().requestId("requestId-2").build();

        StepVerifier.create(adminReadonlyService.listAuditEntries(filter, 0, 10))
                .assertNext(response -> {
                    Assertions.assertThat(response.totalCount()).isEqualTo(1L);
                    Assertions.assertThat(response.items()).hasSize(1);
                    Assertions.assertThat(response.items().getFirst().requestId()).isEqualTo("requestId-2");
                    Assertions.assertThat(response.items().getFirst().actorLogin()).isEqualTo(SECOND_AUDIT_ACTOR_LOGIN);
                })
                .verifyComplete();
    }

    @Test
    void listAuditEntries_byActorUserId_findsOnlyThatActor() {
        FilterAuditDTO filter = FilterAuditDTO.builder()
                .actorUserId(UUID.fromString(SECOND_AUDIT_ACTOR_ID))
                .build();

        assertAuditIds(filter, SECOND_AUDIT_ID);
    }

    @Test
    void listAuditEntries_byTargetTableAndId_matchesEachColumnExactly() {
        assertAuditIds(FilterAuditDTO.builder().targetTable(FIXTURE_TABLE).build(), SECOND_AUDIT_ID, FIRST_AUDIT_ID);
        assertAuditIds(FilterAuditDTO.builder().targetId("00000000-0000-0000-0000-000000000001").build(),
                FIRST_AUDIT_ID);
    }

    @Test
    void listAuditEntries_byCreatedAtFrom_includesLowerBound() {
        // Ровно created_at второй записи: граница включительно, первая запись (2024-01-15) раньше
        FilterAuditDTO filter = FilterAuditDTO.builder()
                .createdAtFrom(Instant.parse("2024-02-01T09:30:00Z"))
                .build();

        assertAuditIds(filter, SECOND_AUDIT_ID);
    }

    @Test
    void listAuditEntries_byCreatedAtTo_includesUpperBound() {
        // Ровно created_at первой записи: граница включительно, вторая запись (2024-02-01) позже
        FilterAuditDTO filter = FilterAuditDTO.builder()
                .createdAtTo(Instant.parse("2024-01-15T12:00:00Z"))
                .build();

        assertAuditIds(filter, FIRST_AUDIT_ID);
    }

    @Test
    void listAuditEntries_byPeriod_excludesEntriesOutsideIt() {
        assertAuditIds(FilterAuditDTO.builder()
                        .createdAtFrom(Instant.parse("2024-01-01T00:00:00Z"))
                        .createdAtTo(Instant.parse("2024-12-31T00:00:00Z"))
                        .build(),
                SECOND_AUDIT_ID, FIRST_AUDIT_ID);
        assertAuditIds(FilterAuditDTO.builder()
                        .createdAtFrom(Instant.parse("2024-01-15T12:00:00.000001Z"))
                        .createdAtTo(Instant.parse("2024-02-01T09:29:59.999999Z"))
                        .build());
    }

    @Test
    void listAuditEntries_withWhitespaceOnlyFilters_ignoresThem() {
        FilterAuditDTO filter = FilterAuditDTO.builder()
                .action("  ")
                .requestId(" ")
                .build();

        assertAuditIds(filter, SECOND_AUDIT_ID, FIRST_AUDIT_ID);
    }

    @Test
    void listAuditEntries_byValueOfOtherEntryInEachFilter_findsNothing() {
        // Все условия объединяются через AND: actor первой записи + action второй не совпадают ни с одной строкой
        FilterAuditDTO filter = FilterAuditDTO.builder()
                .actorUserId(UUID.fromString(FIXTURE_USER_ID))
                .action(SECOND_AUDIT_ACTION)
                .build();

        assertAuditIds(filter);
    }

    @Test
    void listAuditEntries_masksSensitiveColumnsInOldAndNewValues_resolvingServiceSuffix() {
        FilterAuditDTO filter = FilterAuditDTO.builder().action(SECOND_AUDIT_ACTION).build();

        StepVerifier.create(adminReadonlyService.listAuditEntries(filter, 0, 10))
                .assertNext(response -> {
                    Assertions.assertThat(response.items()).hasSize(1);
                    AuditEntriesResponseDto entry = response.items().getFirst();

                    Assertions.assertThat(entry.targetService()).isEqualTo(SECOND_AUDIT_TARGET_SERVICE);
                    Assertions.assertThat(entry.oldValue().get("login").asText()).isEqualTo("bob");
                    Assertions.assertThat(entry.oldValue().get("email").asText()).isEqualTo("***");
                    Assertions.assertThat(entry.newValue().get("login").asText()).isEqualTo("bob");
                    Assertions.assertThat(entry.newValue().get("email").asText()).isEqualTo("***");
                })
                .verifyComplete();
    }

    /** Проверяет и выборку, и totalCount: они строятся из одного фильтра, но разными запросами. */
    private void assertAuditIds(FilterAuditDTO filter, UUID... expectedIdsNewestFirst) {
        StepVerifier.create(adminReadonlyService.listAuditEntries(filter, 0, 10))
                .assertNext(response -> {
                    Assertions.assertThat(response.items())
                            .extracting(AuditEntriesResponseDto::id)
                            .containsExactly(expectedIdsNewestFirst);
                    Assertions.assertThat(response.totalCount()).isEqualTo(expectedIdsNewestFirst.length);
                })
                .verifyComplete();
    }
}
