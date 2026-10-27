package ru.taska.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.api.registry.v1.*;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class WorkflowBindingReceiptRepository {
    private final DatabaseClient db;
    private final ObjectMapper mapper;
    public Mono<TypeOperationOutcome> find(UUID project,UUID operation) {
        return db.sql("SELECT state,changed_count FROM taska.workflow_binding_receipts WHERE project_id=:p AND operation_id=:op")
                .bind("p",project).bind("op",operation).map(row->outcome(row.get("state",String.class),row.get("changed_count",Long.class))).one();
    }
    public Mono<TypeOperationOutcome> insert(WorkflowBindingIntent intent,String state,long count) {
        var scope=intent.getMovesList().stream().map(move->java.util.Map.of("code",move.getCode(),"generation",move.getGeneration())).toList();
        return db.sql("INSERT INTO taska.workflow_binding_receipts(operation_id,project_id,state,changed_count,scope) VALUES(:op,:p,:state,:count,CAST(:scope AS jsonb))")
                .bind("op",UUID.fromString(intent.getOperationId())).bind("p",UUID.fromString(intent.getProjectId()))
                .bind("state",state).bind("count",count).bind("scope",mapper.writeValueAsString(scope)).then().thenReturn(outcome(state,count));
    }
    public Mono<Void> release(UUID project,UUID operation) {
        return db.sql("UPDATE taska.workflow_binding_receipts SET released=true WHERE project_id=:p AND operation_id=:op")
                .bind("p",project).bind("op",operation).then();
    }
    private static TypeOperationOutcome outcome(String state,long count) {
        return TypeOperationOutcome.newBuilder().setState(state.equals("APPLIED")?TypeOperationState.TYPE_OPERATION_STATE_APPLIED:
                TypeOperationState.TYPE_OPERATION_STATE_CANCELLED).setChangedCount(count).build();
    }
}
