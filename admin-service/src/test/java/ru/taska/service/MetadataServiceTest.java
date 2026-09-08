package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.config.props.MetadataCatalogProperties.ServiceProperties;
import ru.taska.config.props.MetadataCatalogProperties.TableProperties;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.DbColumnType;
import ru.taska.domain.PrimaryKeyMetadata;
import ru.taska.dto.ColumnDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.MetadataSchemaRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                new TableProperties(List.of("users", "sessions"), List.of("sessions"), Map.of(), Map.of())
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
        MetadataSchemaRepository repository = repositoryWith(Set.of("auth"));
        Mockito.when(repository.findColumns("auth", "taska")).thenReturn(Flux.just(
                col("users", "id", "uuid"),
                col("users", "login", "character varying")
        ));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("auth", serviceProps("auth-db", allTables()))),
                repository
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
    void getCatalog_queriesSchemaConfiguredForService() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("auth"));
        Mockito.when(repository.findColumns("auth", "custom_schema"))
                .thenReturn(Flux.just(col("users", "id", "uuid")));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of(
                        "auth", new ServiceProperties("auth-db", "custom_schema", allTables())
                )),
                repository
        );

        StepVerifier.create(service.getCatalog())
                .assertNext(catalog -> assertThat(catalog.services()).hasSize(1))
                .verifyComplete();

        Mockito.verify(repository).findColumns("auth", "custom_schema");
        Mockito.verify(repository).findPrimaryKeys("auth", "custom_schema");
    }

    @Test
    void getCatalog_returnsEveryConfiguredService() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("auth", "issue"));
        Mockito.when(repository.findColumns("auth", "taska"))
                .thenReturn(Flux.just(col("users", "id", "uuid")));
        Mockito.when(repository.findColumns("issue", "taska"))
                .thenReturn(Flux.just(col("issues", "id", "uuid")));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of(
                        "auth", serviceProps("auth-db", allTables()),
                        "issue", serviceProps("issue-db", allTables())
                )),
                repository
        );

        StepVerifier.create(service.getCatalog())
                .assertNext(catalog -> assertThat(catalog.services())
                        .extracting(ServiceDto::serviceKey)
                        .containsExactlyInAnyOrder("auth", "issue"))
                .verifyComplete();
    }

    @Test
    void getPrimaryKeyColumn_returnsPkWhenPresent() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("issue"));
        Mockito.when(repository.findPrimaryKeys("issue", "taska"))
                .thenReturn(Flux.just(new PrimaryKeyMetadata("issues", "id")));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repository
        );

        StepVerifier.create(service.getPrimaryKeyColumn("issue", "issues"))
                .expectNext("id")
                .verifyComplete();
    }

    @Test
    void getPrimaryKeyColumn_returnsNotFoundWhenServiceUnknown() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repositoryWith(Set.of("issue"))
        );

        StepVerifier.create(service.getPrimaryKeyColumn("unknown", "issues"))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND
                        && de.getMessage().equals("Service not found: unknown"))
                .verify();
    }

    @Test
    void getTableColumns_returnsNotFoundWhenServiceUnknown() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repositoryWith(Set.of("issue"))
        );

        StepVerifier.create(service.getTableColumns("unknown", "issues"))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND
                        && de.getMessage().equals("Service not found: unknown"))
                .verify();
    }

    @Test
    void getPrimaryKeyColumn_returnsTableNotFoundWhenTableMissing() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("issue"));
        // no PKs, no columns → table does not exist
        Mockito.when(repository.findPrimaryKeys("issue", "taska")).thenReturn(Flux.empty());
        Mockito.when(repository.findColumns("issue", "taska")).thenReturn(Flux.empty());

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repository
        );

        StepVerifier.create(service.getPrimaryKeyColumn("issue", "issues1"))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND
                        && de.getMessage().equals("Table not found: issues1"))
                .verify();
    }

    @Test
    void getPrimaryKeyColumn_returnsNoPrimaryKeyWhenTableExistsWithoutPk() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("issue"));
        Mockito.when(repository.findPrimaryKeys("issue", "taska")).thenReturn(Flux.empty());
        Mockito.when(repository.findColumns("issue", "taska"))
                .thenReturn(Flux.just(col("orphan_table", "payload", "jsonb")));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repository
        );

        StepVerifier.create(service.getPrimaryKeyColumn("issue", "orphan_table"))
                .expectErrorMatches(e -> e instanceof DomainException de
                        && de.getStatus() == DomainStatus.NOT_FOUND
                        && de.getMessage().equals("No primary key found for table: orphan_table"))
                .verify();
    }

    @Test
    void getTableColumns_returnsEmptyMapWhenTableHasNoColumns() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("issue"));
        Mockito.when(repository.findColumns("issue", "taska")).thenReturn(Flux.empty());

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repository
        );

        StepVerifier.create(service.getTableColumns("issue", "issues1"))
                .assertNext(columns -> assertThat(columns).isEmpty())
                .verifyComplete();
    }

    @Test
    void getTableColumns_returnsColumnsWhenTableExists() {
        MetadataSchemaRepository repository = repositoryWith(Set.of("issue"));
        Mockito.when(repository.findColumns("issue", "taska")).thenReturn(Flux.just(
                col("issues", "id", "uuid"),
                col("issues", "title", "character varying")
        ));

        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("issue", serviceProps("issue-db", allTables()))),
                repository
        );

        StepVerifier.create(service.getTableColumns("issue", "issues"))
                .assertNext(columns -> assertThat(columns)
                        .containsExactly(
                                Map.entry("id", DbColumnType.OTHER),
                                Map.entry("title", DbColumnType.TEXT)
                        ))
                .verifyComplete();
    }

    @Test
    void validateClientsConfigured_failsWhenClientIsMissing() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of(
                        "auth", serviceProps("auth-db", allTables()),
                        "issue", serviceProps("issue-db", allTables())
                )),
                repositoryWith(Set.of("auth"))
        );

        assertThatThrownBy(service::validateClientsConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issue");
    }

    @Test
    void validateClientsConfigured_passesWhenEveryServiceHasClient() {
        MetadataService service = new MetadataService(
                new MetadataCatalogProperties(null, MaskType.MASK_FULL, Map.of("auth", serviceProps("auth-db", allTables()))),
                repositoryWith(Set.of("auth"))
        );

        assertThatCode(service::validateClientsConfigured).doesNotThrowAnyException();
    }

    /**
     * Репозиторий с указанными зарегистрированными сервисами; по умолчанию
     * запросы возвращают пустой результат, тесты доопределяют нужное.
     */
    private static MetadataSchemaRepository repositoryWith(Set<String> serviceKeys) {
        MetadataSchemaRepository repository = Mockito.mock(MetadataSchemaRepository.class);
        Mockito.lenient().when(repository.registeredServiceKeys()).thenReturn(serviceKeys);
        Mockito.lenient().when(repository.findColumns(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Flux.empty());
        Mockito.lenient().when(repository.findPrimaryKeys(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Flux.empty());
        return repository;
    }

    private static ServiceProperties serviceProps(String alias, TableProperties tables) {
        return new ServiceProperties(alias, "taska", tables);
    }

    private static ColumnMetadata col(String table, String column, String type) {
        return new ColumnMetadata(table, column, type);
    }

    private static TableProperties allTables() {
        return new TableProperties(List.of(), List.of(), Map.of(), Map.of());
    }

    private static TableProperties allowOnly(String... tables) {
        return new TableProperties(List.of(tables), List.of(), Map.of(), Map.of());
    }

    private static TableProperties denied(String... tables) {
        return new TableProperties(List.of(), List.of(tables), Map.of(), Map.of());
    }

    private static TableProperties sensitive(String... columns) {
        Map<String, String> map = new HashMap<>();
        for (String column : columns) {
            map.put(column, "MASK_FULL");
        }
        return new TableProperties(List.of(), List.of(), map, Map.of());
    }

    private static ColumnDto column(List<TableDto> tables, String tableName, String columnName) {
        return tables.stream()
                .filter(table -> table.name().equals(tableName))
                .flatMap(table -> table.columns().stream())
                .filter(col -> col.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column not found: " + tableName + "." + columnName));
    }
}
