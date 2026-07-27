package ru.taska.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.config.props.MetadataCatalogProperties.ServiceProperties;
import ru.taska.config.props.MetadataCatalogProperties.TableProperties;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.PrimaryKeyMetadata;
import ru.taska.dto.CatalogDto;
import ru.taska.dto.ColumnDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class MetadataService {

    private final MetadataCatalogProperties properties;
    private final Map<String, DatabaseClient> clients;

    public MetadataService(
            MetadataCatalogProperties properties,
            @Qualifier("readonlyDatabaseClients") Map<String, DatabaseClient> clients
    ) {
        this.properties = properties;
        this.clients = clients;
    }

    /**
     * Fail-fast: набор клиентов статичен и известен на старте, поэтому проверяем
     * рассинхрон {@code admin.metadata.services.*} и зарегистрированных клиентов
     * один раз при инициализации, а не на каждом запросе.
     */
    @PostConstruct
    void validateClientsConfigured() {
        Set<String> missing = properties.services().keySet().stream()
                .filter(serviceKey -> !clients.containsKey(serviceKey))
                .collect(Collectors.toCollection(TreeSet::new));

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "No readonly DatabaseClient registered for service keys: " + missing
                            + ". Available clients: " + new TreeSet<>(clients.keySet())
            );
        }
    }

    /**
     * Собирает каталог таблиц для всех сервисов, перечисленных в {@code admin.metadata.services}.
     */
    public Mono<CatalogDto> getCatalog() {
        return Flux.fromIterable(properties.services().entrySet())
                .flatMap(entry -> buildServiceDto(entry.getKey(), entry.getValue()))
                .collectList()
                .map(CatalogDto::new);
    }

    /**
     * Строит описание одного сервиса: alias + список таблиц.
     */
    private Mono<ServiceDto> buildServiceDto(
            String serviceKey,
            ServiceProperties props
    ) {
        /**
         * Наличие клиента гарантировано fail-fast проверкой в validateClientsConfigured().
         */
        DatabaseClient client = clients.get(serviceKey);
        TableProperties tableProps = props.tables();

        return fetchColumns(client)
                .collectList()
                .flatMap(columns ->
                        fetchPrimaryKeys(client)
                                .collectList()
                                .map(pks -> buildTables(columns, pks, tableProps))
                )
                .map(tables -> new ServiceDto(serviceKey, props.alias(), tables));
    }

    private Flux<ColumnMetadata> fetchColumns(DatabaseClient client) {
        return client.sql("""
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'taska'
                ORDER BY table_name, ordinal_position
                """)
                .map((row, rowMetadata) -> new ColumnMetadata(
                        row.get("table_name", String.class),
                        row.get("column_name", String.class),
                        row.get("data_type", String.class)
                ))
                .all();
    }

    private Flux<PrimaryKeyMetadata> fetchPrimaryKeys(DatabaseClient client) {
        return client.sql("""
                SELECT kcu.table_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.constraint_type = 'PRIMARY KEY'
                  AND tc.table_schema = 'taska'
                """)
                .map((row, rowMetadata) -> new PrimaryKeyMetadata(
                        row.get("table_name", String.class),
                        row.get("column_name", String.class)
                ))
                .all();
    }

    /**
     * Преобразует метаданные колонок и PK из {@code information_schema} в DTO каталога
     * с применением правил allow/deny/sensitive из конфигурации.
     */
    static List<TableDto> buildTables(
            List<ColumnMetadata> columns,
            List<PrimaryKeyMetadata> primaryKeys,
            TableProperties tableProps
    ) {
        Set<String> allow = Set.copyOf(tableProps.allow());
        Set<String> deny = Set.copyOf(tableProps.deny());
        Set<String> sensitiveColumns = Set.copyOf(tableProps.sensitiveColumns());

        Set<String> pkKeys = primaryKeys.stream()
                .map(pk -> pk.tableName() + "." + pk.columnName())
                .collect(Collectors.toSet());

        Map<String, List<ColumnMetadata>> columnsByTable = columns.stream()
                .collect(Collectors.groupingBy(
                        ColumnMetadata::tableName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return columnsByTable.entrySet().stream()
                .filter(entry -> isTableVisible(entry.getKey(), allow, deny))
                .map(entry -> {
                    String table = entry.getKey();
                    List<ColumnDto> cols = entry.getValue().stream()
                            .map(c -> new ColumnDto(
                                    c.columnName(),
                                    c.dataType(),
                                    pkKeys.contains(table + "." + c.columnName()),
                                    sensitiveColumns.contains(c.columnName())
                            ))
                            .toList();
                    return new TableDto(table, cols);
                })
                .toList();
    }

    /**
     * Пустой allowlist означает «разрешены все таблицы».
     * Denylist имеет приоритет над allowlist.
     */
    static boolean isTableVisible(String table, Set<String> allow, Set<String> deny) {
        if (deny.contains(table)) {
            return false;
        }
        return allow.isEmpty() || allow.contains(table);
    }
}
