package ru.taska.repository;

import static org.assertj.core.api.Assertions.*;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Upgrade an existing TAS-433 pending deletion, including its populated foreign-key reference. */
@Testcontainers
class ProjectTypeOperationsMigrationIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16");
    @Test void backfillsActiveAndClosedIdentitiesAndRepointsTheExistingRegistryFence() throws Exception {
        var factory=ConnectionFactories.get("r2dbc:postgresql://"+postgres.getUsername()+":"+postgres.getPassword()+"@"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+postgres.getDatabaseName());
        var db=DatabaseClient.create(factory);
        for(String file:java.util.List.of("0000-init.sql","0001-project-issue-types.sql","0002-issue-type-generation.sql","0003-issue-type-shape.sql","0004-issue-type-deletion-intents.sql"))migrate(db,file);
        UUID project=UUID.randomUUID(),actor=UUID.randomUUID(),pending=UUID.randomUUID(),completed=UUID.randomUUID(),generation=UUID.randomUUID();
        db.sql("INSERT INTO taska.projects(id,project_key,name,created_by) VALUES(:p,'UPGRADE','Upgrade',:actor)").bind("p",project).bind("actor",actor).then().block();
        db.sql("INSERT INTO taska.project_issue_types(project_id,code,name,color,icon,shape,sort_order,generation) VALUES(:p,'TASK','Task','blue',NULL,'square',10,:generation)")
                .bind("p",project).bind("generation",generation).then().block();
        db.sql("INSERT INTO taska.issue_type_deletions(id,project_id,source_code,source_generation,actor_user_id,state,request_id,node_id) VALUES(:pending,:p,'TASK',:generation,:actor,'PENDING','old-pending','project'),(:completed,:p,'STORY',:generation,:actor,'COMPLETED','old-completed','project')")
                .bind("pending",pending).bind("completed",completed).bind("p",project).bind("generation",generation).bind("actor",actor).then().block();
        db.sql("UPDATE taska.project_issue_types SET pending_operation_id=:op WHERE project_id=:p").bind("op",pending).bind("p",project).then().block();
        migrate(db,"0005-project-type-operations.sql");
        var operations=new ProjectTypeOperationRepository(db);var types=new ProjectIssueTypeRepository(db);
        assertThat(operations.active(project).block().getOperationId()).isEqualTo(pending.toString());
        assertThat(operations.find(project,completed).block().getActive()).isFalse();
        assertThat(types.find(project,"TASK").block().pendingOperationId()).isEqualTo(pending);
        assertThat(types.find(project,"TASK").block().generation()).isEqualTo(generation);
        db.sql("UPDATE taska.project_issue_types SET pending_operation_id=NULL WHERE project_id=:p").bind("p",project).then().block();
        db.sql("UPDATE taska.project_type_operations SET active=false WHERE id=:op").bind("op",pending).then().block();
        UUID binding=UUID.randomUUID();
        db.sql("INSERT INTO taska.project_type_operations(id,project_id,kind,active) VALUES(:op,:p,'WORKFLOW_BINDINGS',true)").bind("op",binding).bind("p",project).then().block();
        db.sql("UPDATE taska.project_issue_types SET pending_operation_id=:op WHERE project_id=:p").bind("op",binding).bind("p",project).then().block();
        assertThat(types.find(project,"TASK").block().pendingOperationId()).isEqualTo(binding);
        assertThat(db.sql("SELECT count(*) AS n FROM taska.issue_type_deletions WHERE id=:op").bind("op",binding).map(row->row.get("n",Long.class)).one().block()).isZero();
    }
    private static void migrate(DatabaseClient db,String file) throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/changelog/changes",file));
        for(String statement:sql.replaceAll("(?m)--.*$","").split(";"))if(!statement.isBlank())db.sql(statement).then().block();
    }
}
