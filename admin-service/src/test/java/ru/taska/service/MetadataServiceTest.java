package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.config.props.MetadataCatalogProperties.ServiceProperties;
import ru.taska.config.props.MetadataCatalogProperties.TableProperties;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.PrimaryKeyMetadata;
import ru.taska.dto.ColumnDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static ru.taska.service.MetadataService.buildTables;
import static ru.taska.service.MetadataService.isTableVisible;

class MetadataServiceTest {

    @Test
    void buildTables_groupsColumnsAndPreservesTypes() {
        List<TableDto> tables = buildTables(
                List.of(
                        col("users", "id", "uuid"),
                        col("users", "login", "character varying")
                ),
                List.of(),
                allTables()
        );

        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().name()).isEqualTo("users");
        assertThat(tables.getFirst().columns())
                .extracting(ColumnDto::name, ColumnDto::type)
                .containsExactly(
                        tuple("id", "uuid"),
                        tuple("login", "character varying")
                );
    }

    @Test
    void buildTables_marksPrimaryKeyOnlyForOwningTable() {
        List<TableDto> tables = buildTables(
                List.of(
                        col("users", "id", "uuid"),
                        col("sessions", "id", "uuid"),
                        col("sessions", "user_id", "uuid")
                ),
                List.of(new PrimaryKeyMetadata("users", "id")),
                allTables()
        );

        assertThat(column(tables, "users", "id").primaryKey()).isTrue();
        assertThat(column(tables, "sessions", "id").primaryKey()).isFalse();
        assertThat(column(tables, "sessions", "user_id").primaryKey()).isFalse();
    }

    @Test
    void buildTables_marksSensitiveColumnsFromConfig() {
        List<TableDto> tables = buildTables(
                List.of(
                        col("users", "login", "character varying"),
                        col("users", "password_hash", "character varying"),
                        col("audit", "password_hash", "character varying")
                ),
                List.of(),
                sensitive("users.password_hash")
        );

        assertThat(column(tables, "users", "password_hash").sensitive()).isTrue();
        assertThat(column(tables, "users", "login").sensitive()).isFalse();
        assertThat(column(tables, "audit", "password_hash").sensitive()).isFalse();
    }

    @Test
    void buildTables_returnsAllTablesWhenAllowlistIsEmpty() {
        List<TableDto> tables = buildTables(
                List.of(col("users", "id", "uuid"), col("sessions", "id", "uuid")),
                List.of(),
                allTables()
        );

        assertThat(tables).extracting(TableDto::name).containsExactly("users", "sessions");
    }

    @Test
    void buildTables_keepsOnlyAllowedTables() {
        List<TableDto> tables = buildTables(
                List.of(
                        col("users", "id", "uuid"),
                        col("sessions", "id", "uuid"),
                        col("audit_log", "id", "uuid")
                ),
                List.of(),
                allowOnly("users", "sessions")
        );

        assertThat(tables).extracting(TableDto::name).containsExactly("users", "sessions");
    }

    @Test
    void buildTables_hidesDeniedTables() {
        List<TableDto> tables = buildTables(
                List.of(
                        col("users", "id", "uuid"),
                        col("databasechangelog", "id", "character varying")
                ),
                List.of(),
                denied("databasechangelog")
        );

        assertThat(tables).extracting(TableDto::name).containsExactly("users");
    }

    @Test
    void buildTables_appliesDenylistOverAllowlist() {
        List<TableDto> tables = buildTables(
                List.of(col("users", "id", "uuid"), col("sessions", "id", "uuid")),
                List.of(),
                new TableProperties(List.of("users", "sessions"), List.of("sessions"), List.of())
        );

        assertThat(tables).extracting(TableDto::name).containsExactly("users");
    }

    @Test
    void buildTables_returnsEmptyListWhenNoColumns() {
        assertThat(buildTables(List.of(), List.of(), allTables())).isEmpty();
    }

    @Test
    void isTableVisible_emptyAllowMeansAllExceptDenied() {
        assertThat(isTableVisible("users", Set.of(), Set.of())).isTrue();
        assertThat(isTableVisible("users", Set.of(), Set.of("users"))).isFalse();
    }

    @Test
    void isTableVisible_nonEmptyAllowRequiresMembership() {
        assertThat(isTableVisible("users", Set.of("users"), Set.of())).isTrue();
        assertThat(isTableVisible("sessions", Set.of("users"), Set.of())).isFalse();
    }

    @Test
    void getCatalog_returnsServiceWithTablesAndColumnTypes() {
        MetadataService service = serviceWith(
                mockClient(
                        List.of(
                                new ColumnMetadata("users", "id", "uuid"),
                                new ColumnMetadata("users", "login", "character varying")
                        ),
                        List.of()
                ),
                allTables()
        );

        StepVerifier.create(service.getCatalog())
                .assertNext(catalog -> {
                    assertThat(catalog.services()).hasSize(1);

                    ServiceDto auth = catalog.services().getFirst();
                    assertThat(auth.serviceKey()).isEqualTo("auth");
                    assertThat(auth.alias()).isEqualTo("auth-db");
                    assertThat(auth.tables()).hasSize(1);
                    assertThat(auth.tables().getFirst().columns())
                            .extracting(ColumnDto::name, ColumnDto::type)
                            .containsExactly(
                                    tuple("id", "uuid"),
                                    tuple("login", "character varying")
                            );
                })
                .verifyComplete();
    }

    @Test
    void getCatalog_returnsEveryConfiguredService() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(Map.of(
                        "auth", new ServiceProperties("auth-db", allTables()),
                        "issue", new ServiceProperties("issue-db", allTables())
                )),
                Map.of(
                        "auth", mockClient(List.of(new ColumnMetadata("users", "id", "uuid")), List.of()),
                        "issue", mockClient(List.of(new ColumnMetadata("issues", "id", "uuid")), List.of())
                )
        );

        StepVerifier.create(service.getCatalog())
                .assertNext(catalog -> assertThat(catalog.services())
                        .extracting(ServiceDto::serviceKey)
                        .containsExactlyInAnyOrder("auth", "issue"))
                .verifyComplete();
    }

    @Test
    void validateClientsConfigured_failsWhenClientIsMissing() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(Map.of(
                        "auth", new ServiceProperties("auth-db", allTables()),
                        "issue", new ServiceProperties("issue-db", allTables())
                )),
                Map.of("auth", Mockito.mock(DatabaseClient.class))
        );

        assertThatThrownBy(service::validateClientsConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issue");
    }

    @Test
    void validateClientsConfigured_passesWhenEveryServiceHasClient() {
        MetadataService service = serviceWith(Mockito.mock(DatabaseClient.class), allTables());

        assertThatCode(service::validateClientsConfigured).doesNotThrowAnyException();
    }

    private static MetadataService serviceWith(DatabaseClient client, TableProperties tables) {
        return new MetadataService(
                new MetadataCatalogProperties(Map.of("auth", new ServiceProperties("auth-db", tables))),
                Map.of("auth", client)
        );
    }

    private static ColumnMetadata col(String table, String column, String type) {
        return new ColumnMetadata(table, column, type);
    }

    private static TableProperties allTables() {
        return new TableProperties(List.of(), List.of(), List.of());
    }

    private static TableProperties allowOnly(String... tables) {
        return new TableProperties(List.of(tables), List.of(), List.of());
    }

    private static TableProperties denied(String... tables) {
        return new TableProperties(List.of(), List.of(tables), List.of());
    }

    private static TableProperties sensitive(String... columns) {
        return new TableProperties(List.of(), List.of(), List.of(columns));
    }

    private static ColumnDto column(List<TableDto> tables, String tableName, String columnName) {
        return tables.stream()
                .filter(table -> table.name().equals(tableName))
                .flatMap(table -> table.columns().stream())
                .filter(col -> col.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column not found: " + tableName + "." + columnName));
    }

    @SuppressWarnings("unchecked")
    private static DatabaseClient mockClient(
            List<ColumnMetadata> columns,
            List<PrimaryKeyMetadata> primaryKeys
    ) {
        DatabaseClient client = Mockito.mock(DatabaseClient.class);

        DatabaseClient.GenericExecuteSpec columnsSpec = Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec primaryKeysSpec = Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<ColumnMetadata> columnsFetch = Mockito.mock(RowsFetchSpec.class);
        RowsFetchSpec<PrimaryKeyMetadata> primaryKeysFetch = Mockito.mock(RowsFetchSpec.class);

        Mockito.when(client.sql(ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("information_schema.columns")) {
                return columnsSpec;
            }
            if (sql.contains("table_constraints")) {
                return primaryKeysSpec;
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        });

        Mockito.doReturn(columnsFetch).when(columnsSpec).map(ArgumentMatchers.any(BiFunction.class));
        Mockito.doReturn(primaryKeysFetch).when(primaryKeysSpec).map(ArgumentMatchers.any(BiFunction.class));
        Mockito.when(columnsFetch.all()).thenReturn(Flux.fromIterable(columns));
        Mockito.when(primaryKeysFetch.all()).thenReturn(Flux.fromIterable(primaryKeys));

        return client;
    }
}
