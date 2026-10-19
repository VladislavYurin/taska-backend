package ru.taska.integration;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.dto.ListTableRowsRequestDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AuditLogMapper;
import ru.taska.repository.AuditLogRepository;
import ru.taska.repository.MetadataSchemaRepository;
import ru.taska.repository.ReadOnlyRepository;
import ru.taska.service.AdminReadonlyService;
import ru.taska.service.MetadataService;
import ru.taska.service.readonly.AdminReadonlyServiceImpl;
import ru.taska.service.readonly.FilterParser;
import ru.taska.service.readonly.ReadOnlyQueryBuilder;
import ru.taska.service.readonly.ReadOnlyQueryValidator;
import ru.taska.service.readonly.SensitiveDataMaskService;
import tools.jackson.databind.ObjectMapper;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

/** Real PostgreSQL metadata, query building and parameter binding for both affected catalog routes. */
@Testcontainers
class UuidFilterIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    private static final UUID FILTER_ID = UUID.fromString("acd99770-d691-4a67-93c8-2848c017e818");
    private static AdminReadonlyService service;
    private static ReadOnlyRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        // Separate schemas stand in for the independent service databases.
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            for (String schema : List.of("auth", "issue")) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            statement.execute("CREATE TABLE auth.credentials (id uuid PRIMARY KEY, user_id uuid NOT NULL)");
            statement.execute("CREATE TABLE issue.outbox_events (id uuid PRIMARY KEY, aggregate_id uuid NOT NULL)");
            for (String table : List.of("auth.credentials", "issue.outbox_events")) {
                statement.execute("INSERT INTO " + table + " VALUES ('11111111-1111-1111-1111-111111111111', '"
                        + FILTER_ID + "'), ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333')");
            }
        }
        var options = ConnectionFactoryOptions.parse("r2dbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName()).mutate()
                .option(USER, postgres.getUsername()).option(PASSWORD, postgres.getPassword()).build();
        var client = DatabaseClient.create(ConnectionFactories.get(options));
        var clients = Map.of("auth", client, "issue", client);
        var tables = new MetadataCatalogProperties.TableProperties(List.of(), List.of(), Map.of(), Map.of());
        var properties = new MetadataCatalogProperties(
                new MetadataCatalogProperties.PaginationProperties(0, 20, 100), MaskType.MASK_FULL,
                Map.of("auth", new MetadataCatalogProperties.ServiceProperties("auth", "auth", tables),
                        "issue", new MetadataCatalogProperties.ServiceProperties("issue", "issue", tables)));
        repository = new ReadOnlyRepository();
        ReflectionTestUtils.setField(repository, "readonlyDatabaseClient", clients);
        service = new AdminReadonlyServiceImpl(properties, new SensitiveDataMaskService(properties),
                new MetadataService(properties, new MetadataSchemaRepository(clients)), repository,
                new ReadOnlyQueryBuilder(new ReadOnlyQueryValidator(properties)), new FilterParser(),
                Mockito.mock(AuditLogRepository.class), new AuditLogMapper(), new ObjectMapper());
    }

    @ParameterizedTest
    @CsvSource({"auth, credentials, user_id", "issue, outbox_events, aggregate_id"})
    void equalsUuid_filtersRowsAndCount(String serviceKey, String table, String column) {
        var request = request(serviceKey, table, column, FILTER_ID.toString().toUpperCase());
        StepVerifier.create(service.listTableRows(request, "uuid-filter-it", "test-node"))
                .assertNext(response -> {
                    Assertions.assertThat(response.total()).isEqualTo(1);
                    Assertions.assertThat(response.maskedRows()).hasSize(1);
                    Assertions.assertThat(response.maskedRows().getFirst().get(column)).isEqualTo(FILTER_ID);
                }).expectComplete().verify(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"auth, credentials, user_id", "issue, outbox_events, aggregate_id"})
    void unknownUuid_returnsEmptyPage(String serviceKey, String table, String column) {
        StepVerifier.create(service.listTableRows(
                        request(serviceKey, table, column, "00000000-0000-0000-0000-000000000000"), "uuid-filter-it", "test-node"))
                .assertNext(response -> {
                    Assertions.assertThat(response.total()).isZero();
                    Assertions.assertThat(response.maskedRows()).isEmpty();
                }).expectComplete().verify(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"auth, credentials, user_id", "issue, outbox_events, aggregate_id"})
    void invalidUuid_returnsValidationError(String serviceKey, String table, String column) {
        StepVerifier.create(service.listTableRows(
                        request(serviceKey, table, column, "not-a-uuid"), "uuid-filter-it", "test-node"))
                .expectErrorSatisfies(error -> Assertions.assertThat(error)
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                        .hasMessageContaining(column))
                .verify(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"auth, SELECT", "issue, COUNT"})
    void databaseFailure_returnsDiagnosticErrorWithCause(String serviceKey, String operation) {
        var result = operation.equals("SELECT")
                ? repository.executeQuery(serviceKey, "SELECT missing_column FROM auth.credentials", List.of()).then()
                : repository.countRows(serviceKey, "SELECT count(missing_column) FROM issue.outbox_events", List.of()).then();
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> Assertions.assertThat(error)
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("status", DomainStatus.INTERNAL)
                        .hasMessageContaining(serviceKey).hasMessageContaining(operation)
                        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class))
                .verify(Duration.ofSeconds(10));
    }

    private static ListTableRowsRequestDto request(String serviceKey, String table, String column, String value) {
        return new ListTableRowsRequestDto(serviceKey, table, 0, 20, null, null, Map.of(column + ".equals", value));
    }
}
