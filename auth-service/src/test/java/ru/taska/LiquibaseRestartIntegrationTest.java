package ru.taska;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taska.config.AuthLiquibaseConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Boot/Liquibase startup against PostgreSQL's default "$user",public search path. */
@Testcontainers
class LiquibaseRestartIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUsername("taska").withPassword(UUID.randomUUID().toString());

    @Test
    void freshStartAndRepeatedStartsUsePublicDespiteChangedDefaultSchema() throws Exception {
        String db = database();
        migrate(db, true);
        seed(db);
        String history = snapshot(db, "public");
        migrate(db, true);
        migrate(db, true);
        assertPreserved(db, "public", history);
        assertThat(sql(db, "SELECT current_schema()")).isEqualTo("taska");
        assertThat(sql(db, "SELECT to_regclass('taska.databasechangelog') IS NULL")).isEqualTo("t");
    }

    @Test
    void existingPublicHistorySurvivesUpgradeAndRestart() throws Exception {
        String db = database();
        migrate(db, false);
        seed(db);
        String history = snapshot(db, "public");
        migrate(db, true);
        migrate(db, true);
        assertPreserved(db, "public", history);
        assertThat(sql(db, "SELECT to_regclass('taska.databasechangelog') IS NULL")).isEqualTo("t");
    }

    @Test
    void failedOldRestartLeavesPartialShadowButFixedStartupUsesCompletePublicLedger() throws Exception {
        String db = database();
        migrate(db, false);
        seed(db);
        String history = snapshot(db, "public");
        context(db, false).run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasStackTraceContaining("column c.locked_until does not exist"));
        String shadow = snapshot(db, "taska");
        assertThat(Integer.parseInt(sql(db, "SELECT count(*) FROM taska.databasechangelog")))
                .isPositive().isLessThan(Integer.parseInt(sql(db, "SELECT count(*) FROM public.databasechangelog")));
        migrate(db, true);
        migrate(db, true);
        assertPreserved(db, "public", history);
        assertThat(snapshot(db, "taska")).isEqualTo(shadow);
    }

    @Test
    void installationsWithTaskaHistoryKeepTheirExistingLedger() throws Exception {
        String db = database();
        sql(db, "CREATE SCHEMA taska");
        migrate(db, false);
        seed(db);
        String history = snapshot(db, "taska");
        migrate(db, true);
        migrate(db, true);
        assertPreserved(db, "taska", history);
        assertThat(sql(db, "SELECT to_regclass('public.databasechangelog') IS NULL")).isEqualTo("t");
    }

    @Test
    void completeTaskaHistoryWinsOverCompatiblePartialPublicHistory() throws Exception {
        String db = database();
        sql(db, "CREATE SCHEMA taska");
        migrate(db, false);
        seed(db);
        sql(db, "CREATE TABLE public.databasechangelog (LIKE taska.databasechangelog INCLUDING ALL); "
                + "INSERT INTO public.databasechangelog SELECT * FROM taska.databasechangelog WHERE orderexecuted<=2");
        String history = snapshot(db, "taska"), shadow = snapshot(db, "public");
        migrate(db, true);
        assertPreserved(db, "taska", history);
        assertThat(snapshot(db, "public")).isEqualTo(shadow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "identity"})
    void incompatibleHistoriesStopBeforeAnyMigration(String conflict) throws Exception {
        String db = database();
        migrate(db, false);
        seed(db);
        sql(db, "CREATE TABLE taska.databasechangelog (LIKE public.databasechangelog INCLUDING ALL); "
                + "INSERT INTO taska.databasechangelog SELECT * FROM public.databasechangelog");
        sql(db, conflict.equals("checksum")
                ? "UPDATE taska.databasechangelog SET md5sum='9:conflict' WHERE orderexecuted=1"
                : "UPDATE taska.databasechangelog SET id='another-change' WHERE orderexecuted=1");
        String history = snapshot(db, "public"), shadow = snapshot(db, "taska");
        context(db, true).run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasStackTraceContaining("Conflicting Liquibase histories"));
        assertPreserved(db, "public", history);
        assertThat(snapshot(db, "taska")).isEqualTo(shadow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.liquibase.liquibase-schema", "spring.liquibase.default-schema"})
    void explicitSchemaConfigurationRemainsAuthoritative(String property) throws Exception {
        String db = database();
        migrate(db, false);
        seed(db);
        sql(db, "CREATE TABLE taska.databasechangelog (LIKE public.databasechangelog INCLUDING ALL); "
                + "INSERT INTO taska.databasechangelog SELECT * FROM public.databasechangelog; "
                + "UPDATE taska.databasechangelog SET md5sum='9:conflict' WHERE orderexecuted=1");
        String history = snapshot(db, "public"), shadow = snapshot(db, "taska");
        context(db, true).withPropertyValues(property + "=public").run(ctx -> assertThat(ctx).hasNotFailed());
        assertPreserved(db, "public", history);
        assertThat(snapshot(db, "taska")).isEqualTo(shadow);
    }

    private ApplicationContextRunner context(String db, boolean fixed) {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiquibaseAutoConfiguration.class))
                .withInitializer(ctx -> {
                    try {
                        var yaml = new YamlPropertySourceLoader().load("auth-application", new ClassPathResource("application.yml"));
                        yaml.forEach(source -> ctx.getEnvironment().getPropertySources().addLast(source));
                    } catch (Exception error) { throw new IllegalStateException(error); }
                })
                .withPropertyValues("spring.liquibase.url=" + url(db), "spring.liquibase.user=" + postgres.getUsername(),
                        "spring.liquibase.password=" + postgres.getPassword(), "spring.liquibase.analytics-enabled=false");
        return fixed ? runner.withUserConfiguration(AuthLiquibaseConfiguration.class) : runner;
    }

    private void migrate(String db, boolean fixed) { context(db, fixed).run(ctx -> assertThat(ctx).hasNotFailed()); }
    private Connection connection(String db) throws Exception {
        return DriverManager.getConnection(url(db), postgres.getUsername(), postgres.getPassword());
    }
    private String url(String db) { return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db; }
    private String database() throws Exception {
        String db = "restart_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connection(postgres.getDatabaseName()); var query = connection.createStatement()) {
            query.execute("CREATE DATABASE " + db);
        }
        return db;
    }
    private String sql(String db, String sql) throws Exception {
        try (var connection = connection(db); var query = connection.createStatement()) {
            boolean rows = query.execute(sql);
            if (!rows) return "";
            try (var result = query.getResultSet()) { return result.next() ? result.getString(1) : ""; }
        }
    }
    private String snapshot(String db, String schema) throws Exception {
        return sql(db, "SELECT jsonb_agg(to_jsonb(h) ORDER BY orderexecuted)::text FROM " + schema + ".databasechangelog h");
    }
    private void seed(String db) throws Exception {
        sql(db, "INSERT INTO taska.users(id,email,display_name,status,global_role) "
                + "VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','restart@example.invalid','Preserved','ACTIVE','USER'); "
                + "INSERT INTO taska.credentials(user_id,credential_type,secret_hash,algo) "
                + "VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','PASSWORD','preserved-hash','BCRYPT')");
    }
    private void assertPreserved(String db, String schema, String history) throws Exception {
        assertThat(snapshot(db, schema)).isEqualTo(history);
        assertThat(sql(db, "SELECT display_name FROM taska.users WHERE email='restart@example.invalid'")).isEqualTo("Preserved");
        assertThat(sql(db, "SELECT secret_hash FROM taska.credentials WHERE user_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'"))
                .isEqualTo("preserved-hash");
    }
}
