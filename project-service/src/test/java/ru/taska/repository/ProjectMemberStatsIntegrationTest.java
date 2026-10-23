package ru.taska.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProjectMemberStatsIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void windowCountsAllMembersButReturnsFirstFiveAndChecksEachProjectMembership() throws Exception {
        var template = new R2dbcEntityTemplate(ConnectionFactories.get(String.format(
                "r2dbc:postgresql://%s:%s@%s:%d/%s", postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName())));
        var db = template.getDatabaseClient();
        String init = Files.readString(Path.of("src/main/resources/db/changelog/changes/0000-init.sql"));
        for (String sql : init.split(";")) {
            if (!sql.isBlank()) db.sql(sql).fetch().rowsUpdated().block();
        }
        UUID project = UUID.randomUUID(), other = UUID.randomUUID(), actor = new UUID(0, 1);
        for (UUID id : List.of(project, other)) {
            db.sql("INSERT INTO taska.projects(id, project_key, name, created_by) VALUES (:id, :key, 'Project', :actor)")
                    .bind("id", id).bind("key", id.toString()).bind("actor", actor).fetch().rowsUpdated().block();
        }
        for (int i = 1; i <= 7; i++) {
            db.sql("INSERT INTO taska.project_members(project_id, user_id, role, added_at, added_by) "
                            + "VALUES (:project, :user, 'MEMBER', '2026-10-01T00:00:00Z', :actor)")
                    .bind("project", project).bind("user", new UUID(0, i)).bind("actor", actor)
                    .fetch().rowsUpdated().block();
        }
        db.sql("INSERT INTO taska.project_members(project_id, user_id, role, added_by) "
                        + "VALUES (:project, :user, 'ADMIN', :user)")
                .bind("project", other).bind("user", UUID.randomUUID()).fetch().rowsUpdated().block();
        var repository = new R2dbcRepositoryFactory(template).getRepository(ProjectMemberRepository.class);
        var rows = repository.findMemberStatsBatch(List.of(project, other, project), actor).collectList().block();
        assertThat(rows).hasSize(5).allSatisfy(row -> {
            assertThat(row.projectId()).isEqualTo(project);
            assertThat(row.memberCount()).isEqualTo(7);
        });
        assertThat(rows).extracting(r -> r.userId()).containsExactly(
                new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), new UUID(0, 5));
        assertThat(repository.findMemberStatsBatch(List.of(project), UUID.randomUUID()).collectList().block()).isEmpty();
    }
}
