package ru.taska.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.ProjectIssueTypeService;
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;

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
        var service = new ProjectIssueTypeService(repository, null, null);
        var created = service.create(project, actor, "REQUEST", "Заявка", "teal", "headset", 40)
                .as(tx::transactional).block();
        assertThat(created.name()).isEqualTo("Заявка");
        assertThat(created.createdAt()).isNotNull();
        service.update(project, actor, "BUG", "Инцидент", null, null, 5).as(tx::transactional).block();
        assertThat(repository.list(project).collectList().block().getFirst().code()).isEqualTo("BUG");
        var failure = service.create(project, actor, "ROLLBACK", "Rollback", "blue", "flag", 1)
                .then(Mono.error(new IllegalStateException("force rollback"))).as(tx::transactional);
        reactor.test.StepVerifier.create(failure).expectError(IllegalStateException.class).verify();
        assertThat(repository.find(project, "ROLLBACK").block()).isNull();
        assertThat(db.sql("SELECT settings::text AS value FROM taska.project_settings WHERE project_id=:p")
                .bind("p", project).map((r, m) -> r.get("value", String.class)).one().block()).doesNotContain("ROLLBACK");
    }

    @Test
    void concurrentDeletesCannotRemoveLastType() throws Exception {
        migrate("0001-project-issue-types.sql");
        UUID project = project("RACE");
        admin(project);
        repository.seedDefaults(project).block();
        repository.delete(project, "STORY").block();
        var issues = mock(GrpcIssueServiceClient.class);
        when(issues.countIssueTypeUsage(any(), any(), any(), any())).thenReturn(Mono.just(0L));
        var service = new ProjectIssueTypeService(repository, null, issues);
        var results = Flux.merge(
                service.delete(project, actor, "TASK", "r1", "n").as(tx::transactional).materialize(),
                service.delete(project, actor, "BUG", "r2", "n").as(tx::transactional).materialize())
                .collectList().block();
        assertThat(results.stream().filter(signal -> signal.isOnComplete())).hasSize(1);
        assertThat(results.stream().filter(signal -> signal.isOnError()).map(signal -> signal.getThrowable()).toList())
                .singleElement().satisfies(error -> {
                    assertThat(error).isInstanceOf(DomainException.class);
                    assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                });
        assertThat(repository.list(project).collectList().block()).hasSize(1);
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

    private void migrate(String file) throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/changes", file));
        for (String statement : sql.split(";")) {
            if (!statement.replaceAll("(?m)--.*$", "").isBlank()) db.sql(statement).fetch().rowsUpdated().block();
        }
    }
}
