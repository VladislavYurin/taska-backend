package ru.taska.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.integration.spring.Customizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Keep the migration ledger independent of PostgreSQL's changing "$user" search path. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LiquibaseProperties.class)
public class AuthLiquibaseConfiguration {
    @Bean
    Customizer<Liquibase> authLiquibaseHistorySchema(LiquibaseProperties properties) {
        return liquibase -> {
            var database = liquibase.getDatabase();
            try {
                if (StringUtils.hasText(properties.getLiquibaseSchema())) return;
                if (StringUtils.hasText(properties.getDefaultSchema())) {
                    database.setLiquibaseSchemaName(properties.getDefaultSchema());
                    return;
                }
                var connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
                var publicHistory = history(connection, database, "public");
                var taskaHistory = history(connection, database, "taska");
                // A failed old restart can leave a partial shadow ledger. Reuse the complete
                // compatible history, without copying, deleting or marking any changeset.
                // Different checksums or EXECUTED/MARK_RAN records are not interchangeable.
                String schema;
                if (publicHistory.isEmpty() && taskaHistory.isEmpty()) schema = "public";
                else if (publicHistory.containsAll(taskaHistory)) schema = "public";
                else if (taskaHistory.containsAll(publicHistory)) schema = "taska";
                else throw new IllegalStateException("Conflicting Liquibase histories in public and taska; "
                            + "inspect the histories and explicitly configure spring.liquibase.liquibase-schema");
                database.setLiquibaseSchemaName(schema);
            } catch (SQLException error) {
                throw new IllegalStateException("Cannot determine the auth Liquibase history schema", error);
            }
        };
    }

    private static Set<ExecutedChange> history(Connection connection, Database database, String schema) throws SQLException {
        if (!Set.of("public", "taska").contains(schema)) throw new IllegalArgumentException("Unsupported auth history schema");
        String table = database.escapeTableName(null, schema, database.getDatabaseChangeLogTableName());
        try (var exists = connection.prepareStatement("SELECT to_regclass(?)")) {
            exists.setString(1, table);
            try (var rows = exists.executeQuery()) {
                rows.next();
                if (rows.getString(1) == null) return Set.of();
            }
        }
        var history = new HashSet<ExecutedChange>();
        try (var query = connection.createStatement();
                var rows = query.executeQuery("SELECT id, author, filename, md5sum, exectype FROM " + table)) {
            while (rows.next()) history.add(new ExecutedChange(rows.getString(1), rows.getString(2),
                    rows.getString(3), rows.getString(4), rows.getString(5)));
        }
        return history;
    }

    private record ExecutedChange(String id, String author, String filename, String checksum, String executionType) { }
}
