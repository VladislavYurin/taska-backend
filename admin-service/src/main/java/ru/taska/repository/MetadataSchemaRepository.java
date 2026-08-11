package ru.taska.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.PrimaryKeyMetadata;

import java.util.Map;
import java.util.Set;

/**
 * Read-only доступ к {@code information_schema} подключённых сервисных БД.
 *
 * <p>Инкапсулирует SQL и работу с {@link DatabaseClient}: сервисный слой оперирует
 * только ключом сервиса и именем схемы.</p>
 */
@Repository
public class MetadataSchemaRepository {

    private static final String COLUMNS_SQL = """
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = :schema
            ORDER BY table_name, ordinal_position
            """;

    private static final String PRIMARY_KEYS_SQL = """
            SELECT c.relname  AS table_name,
                   a.attname  AS column_name
            FROM pg_index i
            JOIN pg_class c     ON c.oid = i.indrelid
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE i.indisprimary
              AND n.nspname = :schema
              AND a.attnum > 0
              AND NOT a.attisdropped
            """;

    private final Map<String, DatabaseClient> clients;

    public MetadataSchemaRepository(
            @Qualifier("readonlyDatabaseClients") Map<String, DatabaseClient> clients
    ) {
        this.clients = clients;
    }

    /**
     * Ключи сервисов, для которых зарегистрирован read-only клиент.
     */
    public Set<String> registeredServiceKeys() {
        return clients.keySet();
    }

    public Flux<ColumnMetadata> findColumns(String serviceKey, String schema) {
        return client(serviceKey).sql(COLUMNS_SQL)
                .bind("schema", schema)
                .map((row, rowMetadata) -> new ColumnMetadata(
                        row.get("table_name", String.class),
                        row.get("column_name", String.class),
                        row.get("data_type", String.class)
                ))
                .all();
    }

    public Flux<PrimaryKeyMetadata> findPrimaryKeys(String serviceKey, String schema) {
        return client(serviceKey).sql(PRIMARY_KEYS_SQL)
                .bind("schema", schema)
                .map((row, rowMetadata) -> new PrimaryKeyMetadata(
                        row.get("table_name", String.class),
                        row.get("column_name", String.class)
                ))
                .all();
    }

    private DatabaseClient client(String serviceKey) {
        DatabaseClient client = clients.get(serviceKey);
        if (client == null) {
            throw new IllegalStateException("No readonly DatabaseClient registered for service key: " + serviceKey);
        }
        return client;
    }
}
