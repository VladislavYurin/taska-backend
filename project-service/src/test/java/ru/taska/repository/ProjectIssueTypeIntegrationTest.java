package ru.taska.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeEach;
import nullable.NullableField;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.ProjectIssueTypeService;

@Testcontainers
class ProjectIssueTypeIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    private DatabaseClient db;
    private ProjectIssueTypeRepository repository;
    private TransactionalOperator tx;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void init() throws Exception {
        var factory = ConnectionFactories.get(String.format("r2dbc:postgresql://%s:%s@%s:%d/%s",
                postgres.getUsername(), postgres.getPassword(), postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        db = DatabaseClient.create(factory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        repository = new ProjectIssueTypeRepository(db);
        db.sql("DROP SCHEMA IF EXISTS taska CASCADE").fetch().rowsUpdated().block();
        migrate("0000-init.sql");
    }

    @Test
    void migrationPreservesConfiguredSubsetOrderAndDefaultAndSeedsProjectsWithoutSettings() throws Exception {
        UUID configured = project("CONFIG"), plain = project("PLAIN");
        db.sql("INSERT INTO taska.project_settings(project_id,settings,updated_by) VALUES (:p,CAST(:settings AS jsonb),:actor)")
                .bind("p", configured).bind("actor", actor)
                .bind("settings", "{\"allowedIssueTypes\":[\"TASK\",\"BUG\",\"REQUEST\",\"BUG\"],\"defaultIssueType\":\"BUG\",\"other\":true}")
                .fetch().rowsUpdated().block();
        migrate("0001-project-issue-types.sql");
        assertThat(repository.list(configured).collectList().block()).extracting(t -> t.code())
                .containsExactly("BUG", "TASK", "REQUEST");
        assertThat(repository.list(plain).collectList().block()).extracting(t -> t.code())
                .containsExactly("TASK", "BUG", "STORY");
        assertThat(repository.find(configured, "BUG").block().color()).isEqualTo("red");
        repository.syncLegacySettings(configured, actor).block();
        assertThat(db.sql("SELECT settings->>'other' AS value FROM taska.project_settings WHERE project_id=:p")
                .bind("p", configured).map((r, m) -> r.get("value", String.class)).one().block()).isEqualTo("true");
    }

    @Test
    void freshProjectDefaultsAndCustomMetadataRoundTripAndRollbackTogether() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("FRESH");
        admin(project);
        repository.seedDefaults(project).as(tx::transactional).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        var created = insertFixtureType(project, "REQUEST", "Заявка", "teal", "headset", null, 40)
                .as(tx::transactional).block();
        assertThat(created.name()).isEqualTo("Заявка");
        assertThat(created.createdAt()).isNotNull();
        service.update(project, actor, "BUG", "Инцидент", null, NullableField.absent(), null, 5).as(tx::transactional).block();
        assertThat(repository.list(project).collectList().block().getFirst().code()).isEqualTo("BUG");
        var failure = insertFixtureType(project, "ROLLBACK", "Rollback", "blue", "flag", null, 1)
                .then(Mono.error(new IllegalStateException("force rollback"))).as(tx::transactional);
        reactor.test.StepVerifier.create(failure).expectError(IllegalStateException.class).verify();
        assertThat(repository.find(project, "ROLLBACK").block()).isNull();
        assertThat(db.sql("SELECT settings::text AS value FROM taska.project_settings WHERE project_id=:p")
                .bind("p", project).map((r, m) -> r.get("value", String.class)).one().block()).doesNotContain("ROLLBACK");
    }

    @Test
    void duplicateCodeIsScopedToProjectAndCheckedByDatabase() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID one = project("ONE"), two = project("TWO");
        repository.seedDefaults(one).block();
        repository.seedDefaults(two).block();
        assertThat(repository.find(one, "TASK").block()).isNotNull();
        assertThat(repository.find(two, "TASK").block()).isNotNull();
        reactor.test.StepVerifier.create(repository.seedDefaults(one))
                .expectError(org.springframework.dao.DuplicateKeyException.class).verify();
    }

    @Test
    void invalidLegacyKeysHaltLiquibaseBeforeCreatingRegistry() throws Exception {
        UUID project = project("INVALID");
        db.sql("INSERT INTO taska.project_settings(project_id,settings,updated_by) VALUES (:p,'{\"allowedIssueTypes\":[\"task\"]}',:actor)")
                .bind("p", project).bind("actor", actor).fetch().rowsUpdated().block();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var liquibase = new liquibase.Liquibase("db/changelog/changes/0001-project-issue-types.sql",
                     new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(connection))) {
            assertThatThrownBy(() -> liquibase.update(new liquibase.Contexts(), new liquibase.LabelExpression()))
                    .isInstanceOf(liquibase.exception.LiquibaseException.class)
                    .hasRootCauseInstanceOf(liquibase.exception.PreconditionFailedException.class);
        }
        assertThat(db.sql("SELECT count(*) AS n FROM information_schema.tables WHERE table_schema='taska' AND table_name='project_issue_types'")
                .map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
        assertThat(db.sql("SELECT settings->'allowedIssueTypes'->>0 AS value FROM taska.project_settings WHERE project_id=:p")
                .bind("p", project).map((r, m) -> r.get("value", String.class)).one().block()).isEqualTo("task");
    }

    @Test
    void settingsMirrorSelectsFirstRemainingTypeBySortOrder() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("DEFAULT");
        admin(project);
        repository.seedDefaults(project).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        service.update(project, actor, "BUG", null, null, NullableField.absent(), null, 0).as(tx::transactional).block();
        assertThat(defaultType(project)).isEqualTo("BUG");
        repository.delete(project, "BUG").then(repository.syncLegacySettings(project, actor)).as(tx::transactional).block();
        assertThat(defaultType(project)).isEqualTo("TASK");
        assertThat(repository.list(project).collectList().block().getFirst().code()).isEqualTo("TASK");
    }

    @Test
    void reorderIsCompletePermutationAndRollsBackWithSettings() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("ORDER"); admin(project); repository.seedDefaults(project).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        var ordered = service.reorder(project, actor, List.of("STORY", "TASK", "BUG")).as(tx::transactional).block();
        assertThat(ordered).extracting(t -> t.code()).containsExactly("STORY", "TASK", "BUG");
        assertThat(ordered).extracting(t -> t.sortOrder()).containsExactly(10, 20, 30);
        assertThat(defaultType(project)).isEqualTo("STORY");
        var generation = ordered.getFirst().generation();
        for (var invalid : List.of(List.of("TASK", "BUG"), List.of("TASK", "BUG", "UNKNOWN"))) {
            reactor.test.StepVerifier.create(service.reorder(project, actor, invalid).as(tx::transactional))
                    .expectErrorSatisfies(error -> {
                        assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.ABORTED);
                        assertThat(((DomainException) error).getDetails()).containsEntry("reason", "ISSUE_TYPES_CHANGED");
                    }).verify();
        }
        reactor.test.StepVerifier.create(service.reorder(project, actor, List.of("TASK", "BUG", "BUG")).as(tx::transactional))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT)).verify();
        reactor.test.StepVerifier.create(service.reorder(project, actor, List.of("BUG", "TASK", "STORY"))
                        .then(Mono.error(new IllegalStateException("rollback"))).as(tx::transactional))
                .expectError(IllegalStateException.class).verify();
        assertThat(repository.list(project).collectList().block()).extracting(t -> t.code()).containsExactly("STORY", "TASK", "BUG");
        assertThat(defaultType(project)).isEqualTo("STORY");
        assertThat(repository.find(project, "STORY").block().generation()).isEqualTo(generation);
    }

    @Test
    void deletedCodeGetsDifferentGenerationAndRollbackPreservesIdentity() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("GEN"); admin(project); repository.seedDefaults(project).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        var original = repository.find(project, "BUG").block().generation();
        reactor.test.StepVerifier.create(repository.delete(project, "BUG").then(repository.syncLegacySettings(project, actor))
                        .then(Mono.error(new IllegalStateException("rollback"))).as(tx::transactional))
                .expectError(IllegalStateException.class).verify();
        assertThat(repository.find(project, "BUG").block().generation()).isEqualTo(original);
        repository.delete(project, "BUG").then(repository.syncLegacySettings(project, actor)).as(tx::transactional).block();
        var recreated = insertFixtureType(project, "BUG", "New bug", "red", null, "circle", 20).as(tx::transactional).block();
        assertThat(recreated.generation()).isNotEqualTo(original);
        assertThat(repository.listBatch(List.of(project)).collectList().block()).hasSize(3);
    }

    @Test
    void staleOrderAfterRegistryAdditionOrDeletionReturnsConflictWithoutChangingSettings() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("STALE"); admin(project); repository.seedDefaults(project).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        List<String> old = repository.list(project).map(type -> type.code()).collectList().block();
        insertFixtureType(project, "REQUEST", "Request", "blue", "headset", null, 40).as(tx::transactional).block();
        var afterAddition = repository.list(project).collectList().block();
        reactor.test.StepVerifier.create(service.reorder(project, actor, old).as(tx::transactional))
                .expectErrorSatisfies(error -> {
                    assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.ABORTED);
                    assertThat(((DomainException) error).getDetails()).containsExactlyEntriesOf(java.util.Map.of("reason", "ISSUE_TYPES_CHANGED"));
                }).verify();
        assertThat(repository.list(project).collectList().block()).isEqualTo(afterAddition);
        assertThat(defaultType(project)).isEqualTo(old.getFirst());
        List<String> stale = afterAddition.stream().map(type -> type.code()).toList();
        repository.delete(project, "REQUEST").then(repository.syncLegacySettings(project, actor)).as(tx::transactional).block();
        reactor.test.StepVerifier.create(service.reorder(project, actor, stale).as(tx::transactional))
                .expectErrorSatisfies(error -> {
                    assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.ABORTED);
                    assertThat(((DomainException) error).getDetails()).containsEntry("reason", "ISSUE_TYPES_CHANGED");
                }).verify();
        assertThat(repository.list(project).map(type -> type.code()).collectList().block()).containsExactlyElementsOf(old);
        assertThat(defaultType(project)).isEqualTo(old.getFirst());
    }

    @Test
    void shapeMigrationGivesUntouchedBuiltInsThePlainLookAndKeepsEveryOtherIcon() throws Exception {
        UUID plain = project("PLAIN"), configured = project("CONFIG");
        db.sql("INSERT INTO taska.project_settings(project_id,settings,updated_by) VALUES (:p,CAST(:settings AS jsonb),:actor)")
                .bind("p", configured).bind("actor", actor)
                .bind("settings", "{\"allowedIssueTypes\":[\"TASK\",\"BUG\",\"REQUEST\"]}").fetch().rowsUpdated().block();
        execute("0001-project-issue-types.sql");
        execute("0002-issue-type-generation.sql");
        // An admin edited a built-in icon before TAS-432, and a custom type borrowed a seed icon.
        db.sql("UPDATE taska.project_issue_types SET icon='headset' WHERE project_id=:p AND code='TASK'")
                .bind("p", plain).fetch().rowsUpdated().block();
        db.sql("UPDATE taska.project_issue_types SET icon='flag' WHERE project_id=:p AND code='BUG'")
                .bind("p", configured).fetch().rowsUpdated().block();
        db.sql("INSERT INTO taska.project_issue_types(project_id,code,name,color,icon,sort_order) VALUES (:p,'INCIDENT','Incident','red','bug',40)")
                .bind("p", configured).fetch().rowsUpdated().block();
        var updatedBefore = updatedAt();
        execute("0003-issue-type-shape.sql");
        execute("0004-issue-type-deletion-intents.sql");

        assertThat(repository.list(plain).collectList().block())
                .extracting(t -> t.code() + ":" + t.icon() + ":" + t.shape())
                .containsExactly("TASK:headset:square", "BUG:null:circle", "STORY:null:square");
        assertThat(repository.list(configured).collectList().block())
                .extracting(t -> t.code() + ":" + t.icon() + ":" + t.shape())
                .containsExactly("TASK:null:square", "BUG:flag:circle", "REQUEST:file-text:square", "INCIDENT:bug:square");
        // The backfill restyles rows; it is not an edit, so updated_at stays where it was.
        assertThat(updatedAt()).isEqualTo(updatedBefore);
        assertThat(db.sql("SELECT count(*) AS n FROM taska.project_issue_types WHERE shape IS NULL")
                .map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
        reactor.test.StepVerifier.create(db.sql("UPDATE taska.project_issue_types SET shape='hexagon' WHERE project_id=:p")
                        .bind("p", plain).fetch().rowsUpdated())
                .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
        reactor.test.StepVerifier.create(db.sql("UPDATE taska.project_issue_types SET icon='rocket' WHERE project_id=:p")
                        .bind("p", plain).fetch().rowsUpdated())
                .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
        reactor.test.StepVerifier.create(db.sql("UPDATE taska.project_issue_types SET shape=NULL WHERE project_id=:p")
                        .bind("p", plain).fetch().rowsUpdated())
                .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
    }

    @Test
    void newProjectSeedHasPlainBuiltIns() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("SEED");
        repository.seedDefaults(project).block();
        assertThat(repository.list(project).collectList().block())
                .extracting(t -> String.join(":", t.code(), t.name(), t.color(), String.valueOf(t.icon()), t.shape(),
                        String.valueOf(t.sortOrder())))
                .containsExactly("TASK:Task:blue:null:square:10", "BUG:Bug:red:null:circle:20",
                        "STORY:Story:green:null:square:30");
    }

    @Test
    void shapeAndOptionalIconRoundTripThroughCreateAndPatch() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("LOOK"); admin(project); repository.seedDefaults(project).block();
        var service = new ProjectIssueTypeService(repository, null, new ru.taska.service.ProjectWriteGuard(db), new ProjectTypeOperationRepository(db));
        var plain = insertFixtureType(project, "REQUEST", "Request", "teal", null, null, 40).as(tx::transactional).block();
        assertThat(plain.icon()).isNull();
        assertThat(plain.shape()).isEqualTo("square");
        var milestone = insertFixtureType(project, "MILESTONE", "Milestone", "amber", "flag", "diamond", 50)
                .as(tx::transactional).block();
        assertThat(repository.find(project, "MILESTONE").block()).isEqualTo(milestone);
        assertThat(milestone.icon()).isEqualTo("flag");
        assertThat(milestone.shape()).isEqualTo("diamond");

        service.update(project, actor, "MILESTONE", "Release", null, NullableField.absent(), null, null).as(tx::transactional).block();
        assertThat(repository.find(project, "MILESTONE").block().icon()).isEqualTo("flag");
        service.update(project, actor, "MILESTONE", null, null, NullableField.of(null), "circle", null).as(tx::transactional).block();
        var cleared = repository.find(project, "MILESTONE").block();
        assertThat(cleared.icon()).isNull();
        assertThat(cleared.shape()).isEqualTo("circle");
        assertThat(cleared.name()).isEqualTo("Release");
        service.update(project, actor, "BUG", null, null, NullableField.of("bug"), null, null).as(tx::transactional).block();
        assertThat(repository.find(project, "BUG").block().icon()).isEqualTo("bug");
        assertThat(repository.find(project, "BUG").block().shape()).isEqualTo("circle");
    }

    @Test
    void shapeChangesetRollsBackToTheIconsLegacyReadersSee() throws Exception {
        db.sql("DROP SCHEMA IF EXISTS taska CASCADE").fetch().rowsUpdated().block();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var liquibase = new liquibase.Liquibase("db/changelog/issue-type-shape-rollback.yaml",
                     new liquibase.resource.ClassLoaderResourceAccessor(), new liquibase.database.jvm.JdbcConnection(connection))) {
            // A dedicated changelog fixes the boundary by identity, not the master's changing count.
            liquibase.update(new liquibase.Contexts(), new liquibase.LabelExpression());
            UUID project = project("ROLLBACK");
            repository.seedDefaults(project).block();
            db.sql("INSERT INTO taska.project_issue_types(project_id,code,name,color,icon,shape,sort_order) VALUES "
                            + "(:p,'MILESTONE','Milestone','amber',NULL,'diamond',40),(:p,'REQUEST','Request','teal','headset','square',50)")
                    .bind("p", project).fetch().rowsUpdated().block();
            var before = db.sql("SELECT code,shape,icon FROM taska.project_issue_types WHERE project_id=:p ORDER BY sort_order")
                    .bind("p", project).map((r, m) -> r.get("code", String.class) + ":" + client.ProjectIssueTypes
                            .writeLook(ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder(), r.get("code", String.class),
                                    r.get("shape", String.class), r.get("icon", String.class)).getLegacyIcon())
                    .all().collectList().block();

            liquibase.rollback(1, new liquibase.Contexts(), new liquibase.LabelExpression());

            var icons = db.sql("SELECT code, icon FROM taska.project_issue_types WHERE project_id=:p ORDER BY sort_order")
                    .bind("p", project).map((r, m) -> r.get("code", String.class) + ":" + r.get("icon", String.class)).all()
                    .collectList().block();
            assertThat(icons).containsExactly("TASK:check-square", "BUG:bug", "STORY:book-open",
                    "MILESTONE:check-square", "REQUEST:headset");
            // The rollback must hand back exactly what pre-TAS-432 readers already see in legacy_icon.
            assertThat(icons).containsExactlyElementsOf(before);
            assertThat(db.sql("SELECT count(*) AS n FROM information_schema.columns WHERE table_schema='taska' "
                    + "AND table_name='project_issue_types' AND column_name='shape'").map((r, m) -> r.get("n", Long.class))
                    .one().block()).isZero();
            reactor.test.StepVerifier.create(db.sql("UPDATE taska.project_issue_types SET icon=NULL WHERE project_id=:p")
                            .bind("p", project).fetch().rowsUpdated())
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
        } finally {
            db.sql("DROP TABLE IF EXISTS public.databasechangelog, public.databasechangeloglock").fetch().rowsUpdated().block();
        }
    }

    /** Fixture setup only: public creation is exercised by the durable creation coordinator tests. */
    private Mono<ru.taska.domain.ProjectIssueType> insertFixtureType(UUID project, String code, String name, String color,
            String icon, String shape, Integer sortOrder) {
        return repository.insert(new ru.taska.domain.ProjectIssueType(project, code, name, color, icon,
                        shape == null ? "square" : shape, sortOrder == null ? 100 : sortOrder, null, null))
                .flatMap(saved -> repository.syncLegacySettings(project, actor).thenReturn(saved));
    }

    private java.util.Map<String, java.time.OffsetDateTime> updatedAt() {
        return db.sql("SELECT project_id, code, updated_at FROM taska.project_issue_types")
                .map((r, m) -> java.util.Map.entry(r.get("project_id", UUID.class) + ":" + r.get("code", String.class),
                        r.get("updated_at", java.time.OffsetDateTime.class))).all()
                .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue).block();
    }

    private String defaultType(UUID project) {
        return db.sql("SELECT settings->>'defaultIssueType' AS value FROM taska.project_settings WHERE project_id=:p")
                .bind("p", project).map((r, m) -> r.get("value", String.class)).one().block();
    }

    private UUID project(String key) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO taska.projects(id,project_key,name,created_by) VALUES (:id,:key,'Project',:actor)")
                .bind("id", id).bind("key", key).bind("actor", actor).fetch().rowsUpdated().block();
        return id;
    }

    private void admin(UUID project) {
        db.sql("INSERT INTO taska.project_members(project_id,user_id,role,added_by) VALUES (:p,:actor,'ADMIN',:actor)")
                .bind("p", project).bind("actor", actor).fetch().rowsUpdated().block();
    }

    /** Applies a registry changeset and every later one, so tests run against the current schema. */
    private void migrate(String file) throws Exception {
        execute(file);
        if (file.equals("0001-project-issue-types.sql")) migrate("0002-issue-type-generation.sql");
        if (file.equals("0002-issue-type-generation.sql")) migrate("0003-issue-type-shape.sql");
        if (file.equals("0003-issue-type-shape.sql")) migrate("0004-issue-type-deletion-intents.sql");
        if (file.equals("0004-issue-type-deletion-intents.sql")) migrate("0005-project-type-operations.sql");
    }

    private void execute(String file) throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/changes", file));
        for (String statement : sql.replaceAll("(?m)--.*$", "").split(";")) {
            if (!statement.replaceAll("(?m)--.*$", "").isBlank()) db.sql(statement).fetch().rowsUpdated().block();
        }
    }
}
