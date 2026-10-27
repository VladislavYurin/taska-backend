package ru.taska.repository;

import io.r2dbc.spi.Row;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.WorkflowBindingChange;
import ru.taska.exception.DomainException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class WorkflowBindingChangeRepository {
    private final DatabaseClient database;
    private final ObjectMapper mapper;
    private static final String SELECT="SELECT *,command_json::text AS command_text,plan_json::text AS plan_text,error_details::text AS error_text FROM taska.workflow_binding_changes ";

    public Mono<WorkflowBindingChange> get(UUID project, UUID operation) {
        return database.sql(SELECT+"WHERE project_id=:project AND id=:id")
                .bind("project",project).bind("id",operation).map((r,m)->read(r)).one();
    }
    public Mono<WorkflowBindingChange> byRequest(UUID project, UUID actor, String request) {
        return database.sql(SELECT+"WHERE project_id=:project AND actor_user_id=:actor AND request_id=:request")
                .bind("project",project).bind("actor",actor).bind("request",request).map((r,m)->read(r)).one();
    }
    public Mono<WorkflowBindingChange> pending(UUID project) {
        return database.sql(SELECT+"WHERE project_id=:project AND reservation_released=false ORDER BY created_at,id LIMIT 1")
                .bind("project",project).map((r,m)->read(r)).one();
    }
    public Mono<Boolean> matchesCommand(WorkflowBindingChange item, String command) {
        return database.sql("SELECT command_json=CAST(:command AS jsonb) FROM taska.workflow_binding_changes WHERE id=:id")
                .bind("id",item.id()).bind("command",command).map((r,m)->Boolean.TRUE.equals(r.get(0,Boolean.class))).one();
    }
    public Mono<WorkflowBindingChange> insert(WorkflowBindingChange item) {
        var s=database.sql("""
                INSERT INTO taska.workflow_binding_changes(id,project_id,workflow_id,actor_user_id,kind,
                    request_id,node_id,channel,token_id,command_json,plan_json,state)
                VALUES(:id,:project,:workflow,:actor,:kind,:request,:node,:channel,:token,
                    CAST(:command AS jsonb),CAST(:plan AS jsonb),'PENDING')
                """).bind("id",item.id()).bind("project",item.project()).bind("workflow",item.workflow())
                .bind("actor",item.actor()).bind("kind",item.kind()).bind("request",item.requestId()).bind("node",item.nodeId())
                .bind("command",item.commandJson()).bind("plan",mapper.writeValueAsString(item.plan()));
        s=item.attribution().channel()==null?s.bindNull("channel",String.class):s.bind("channel",item.attribution().channel().name());
        s=item.attribution().tokenId()==null?s.bindNull("token",UUID.class):s.bind("token",item.attribution().tokenId());
        return s.fetch().rowsUpdated().then(get(item.project(),item.id()));
    }
    public Mono<WorkflowBindingChange> cancelling(WorkflowBindingChange item, DomainException error) {
        return database.sql("""
                UPDATE taska.workflow_binding_changes SET state='CANCELLING',error_status=:status,
                    error_message=:message,error_details=CAST(:details AS jsonb),updated_at=now()
                WHERE id=:id AND state='PENDING'
                """).bind("id",item.id()).bind("status",error.getStatus().name()).bind("message",error.getMessage())
                .bind("details",mapper.writeValueAsString(error.getDetails())).fetch().rowsUpdated().then(get(item.project(),item.id()));
    }
    public Mono<Void> completed(WorkflowBindingChange item, byte[] result) {
        return database.sql("UPDATE taska.workflow_binding_changes SET state='COMPLETED',result_bytes=:result,updated_at=now() WHERE id=:id AND state IN ('PENDING','CANCELLING')")
                .bind("id",item.id()).bind("result",result).fetch().rowsUpdated().then();
    }
    public Mono<Void> cancelled(WorkflowBindingChange item) {
        return database.sql("UPDATE taska.workflow_binding_changes SET state='CANCELLED',updated_at=now() WHERE id=:id AND state='CANCELLING'")
                .bind("id",item.id()).fetch().rowsUpdated().then();
    }
    public Mono<Void> released(WorkflowBindingChange item) {
        return database.sql("UPDATE taska.workflow_binding_changes SET reservation_released=true,updated_at=now() WHERE id=:id AND state IN ('COMPLETED','CANCELLED')")
                .bind("id",item.id()).fetch().rowsUpdated().then();
    }
    public Mono<Void> touched(WorkflowBindingChange item) {
        return database.sql("UPDATE taska.workflow_binding_changes SET updated_at=now() WHERE id=:id")
                .bind("id",item.id()).fetch().rowsUpdated().then();
    }
    public Flux<WorkflowBindingChange> recoverable() {
        return database.sql(SELECT+"WHERE reservation_released=false ORDER BY updated_at,id LIMIT 20")
                .map((r,m)->read(r)).all();
    }
    private WorkflowBindingChange read(Row row) {
        String channel=row.get("channel",String.class);
        return new WorkflowBindingChange(row.get("id",UUID.class),row.get("project_id",UUID.class),
                row.get("workflow_id",UUID.class),row.get("actor_user_id",UUID.class),row.get("kind",String.class),
                row.get("request_id",String.class),row.get("node_id",String.class),new ActionMetadata(
                        channel==null?null:ActionChannel.valueOf(channel),row.get("token_id",UUID.class)),
                row.get("command_text",String.class),mapper.readValue(row.get("plan_text",String.class),WorkflowBindingChange.Plan.class),
                row.get("state",String.class),row.get("error_status",String.class),row.get("error_message",String.class),
                mapper.readValue(row.get("error_text",String.class),new TypeReference<Map<String,String>>(){}),
                row.get("result_bytes",byte[].class),Boolean.TRUE.equals(row.get("reservation_released",Boolean.class)));
    }
}
