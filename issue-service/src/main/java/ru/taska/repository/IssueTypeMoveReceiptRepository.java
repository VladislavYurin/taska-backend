package ru.taska.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;

@Repository
@RequiredArgsConstructor
public class IssueTypeMoveReceiptRepository {
  private final DatabaseClient db;
  public Mono<IssueTypeMoveOutcome> find(UUID project,UUID operation) {
    return db.sql("SELECT state,moved_count FROM taska.issue_type_move_receipts WHERE project_id=:project AND operation_id=:operation")
        .bind("project",project).bind("operation",operation).map((r,m)->outcome(r.get("state",String.class),r.get("moved_count",Long.class))).one();
  }
  public Mono<IssueTypeMoveOutcome> insert(IssueTypeDeletionIntent intent,String state,long count) {
    var sql=db.sql("""
        INSERT INTO taska.issue_type_move_receipts(operation_id,project_id,source_code,source_generation,target_code,target_generation,state,moved_count)
        VALUES(:operation,:project,:source,:generation,:target,:targetGeneration,:state,:count)
        """).bind("operation",UUID.fromString(intent.getOperationId())).bind("project",UUID.fromString(intent.getProjectId()))
        .bind("source",intent.getSourceCode()).bind("generation",UUID.fromString(intent.getSourceGeneration()))
        .bind("state",state).bind("count",count);
    sql=intent.hasTargetCode()?sql.bind("target",intent.getTargetCode()).bind("targetGeneration",UUID.fromString(intent.getTargetGeneration()))
        :sql.bindNull("target",String.class).bindNull("targetGeneration",UUID.class);
    return sql.fetch().rowsUpdated().thenReturn(outcome(state,count));
  }
  private static IssueTypeMoveOutcome outcome(String state,long count){return IssueTypeMoveOutcome.newBuilder()
      .setState(state.equals("APPLIED")?IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED:IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED)
      .setMovedCount(count).build();}
}
