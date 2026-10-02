package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.dto.*;
import ru.taska.entity.AuditLog;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for the three AdminService RPC use-cases at the service layer.
 * Pure validation / SQL-building logic is covered by unit tests — here only wiring + real Postgres.
 */
class AdminReadOnlyServiceIT extends AbstractIT {

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


}
