package ru.taska.projectstorage;

import io.r2dbc.spi.Row;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.*;
import ru.taska.exception.DomainException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor(access = lombok.AccessLevel.PACKAGE)
public class IssueTypeCreationRepository {
    private final DatabaseClient db;
    private final UUID tenant;
    private final java.util.function.Supplier<RecoveryStamp> authority;
    private final ObjectMapper mapper;
    private static final String SELECT="SELECT *,error_details::text AS error_json FROM taska.issue_type_creations ";
    public Mono<IssueTypeCreation> find(UUID project,UUID operation) {
        return sql(SELECT+"WHERE tenant_id=:tenant AND project_id=:p AND id=:id").bind("p",project).bind("id",operation).map((r,m)->read(r)).one();
    }
    public Mono<IssueTypeCreation> request(UUID project,UUID actor,String request) {
        return sql(SELECT+"WHERE tenant_id=:tenant AND project_id=:p AND actor_user_id=:actor AND request_id=:request")
                .bind("p",project).bind("actor",actor).bind("request",request).map((r,m)->read(r)).one();
    }
    public Mono<IssueTypeCreation> insert(IssueTypeCreation item) {
        authority.get().requireActor(item.actor());
        var t=item.type();
        var sql=insertSql("""
                INSERT INTO taska.issue_type_creations(tenant_id,id,project_id,actor_user_id,request_id,node_id,command_hash,
                  code,generation,name,color,icon,shape,sort_order,desired_workflow_id,first_type_code,first_type_generation,
                  channel,token_id,state,grant_id,actor_auth_version,membership_version)
                VALUES(:tenant,:id,:p,:actor,:request,:node,:hash,:code,:generation,:name,:color,:icon,:shape,:sort,:workflow,
                  :first,:firstGeneration,:channel,:token,'PENDING',:grant,:authVersion,:memberVersion)
                """).bind("id",item.id()).bind("p",item.projectId()).bind("actor",item.actor()).bind("request",item.requestId())
                .bind("node",item.nodeId()).bind("hash",item.commandHash()).bind("code",t.code()).bind("generation",t.generation())
                .bind("name",t.name()).bind("color",t.color()).bind("shape",t.shape()).bind("sort",t.sortOrder());
        sql=item.firstTypeCode()==null?sql.bindNull("first",String.class).bindNull("firstGeneration",UUID.class)
                :sql.bind("first",item.firstTypeCode()).bind("firstGeneration",item.firstTypeGeneration());
        sql=t.icon()==null?sql.bindNull("icon",String.class):sql.bind("icon",t.icon());
        sql=item.desiredWorkflowId()==null?sql.bindNull("workflow",UUID.class):sql.bind("workflow",item.desiredWorkflowId());
        sql=item.attribution().channel()==null?sql.bindNull("channel",String.class):sql.bind("channel",item.attribution().channel().name());
        sql=item.attribution().tokenId()==null?sql.bindNull("token",UUID.class):sql.bind("token",item.attribution().tokenId());
        return sql.then().then(find(item.projectId(),item.id()));
    }
    public Mono<IssueTypeCreation> cancelling(IssueTypeCreation item,DomainException error) {
        return sql("UPDATE taska.issue_type_creations SET state='CANCELLING',error_status=:status,error_message=:message,error_details=CAST(:details AS jsonb),updated_at=now() WHERE tenant_id=:tenant AND id=:id AND state='PENDING'")
                .bind("id",item.id()).bind("status",error.getStatus().name()).bind("message",error.getMessage())
                .bind("details",mapper.writeValueAsString(error.getDetails())).then().then(find(item.projectId(),item.id()));
    }
    public Mono<Void> completed(IssueTypeCreation item,UUID workflow) {
        return sql("UPDATE taska.issue_type_creations SET state='COMPLETED',resolved_workflow_id=:workflow,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND state IN ('PENDING','CANCELLING')")
                .bind("id",item.id()).bind("workflow",workflow).then();
    }
    public Mono<Void> cancelled(IssueTypeCreation item) {
        return sql("UPDATE taska.issue_type_creations SET state='CANCELLED',updated_at=now() WHERE tenant_id=:tenant AND id=:id AND state IN ('PENDING','CANCELLING')")
                .bind("id",item.id()).then();
    }
    public Flux<IssueTypeCreation> recoverable() {
        return sql(SELECT+"WHERE tenant_id=:tenant AND state IN ('PENDING','CANCELLING') ORDER BY updated_at,id LIMIT 20").map((r,m)->read(r)).all();
    }
    public Mono<Void> touched(IssueTypeCreation item) {
        return sql("UPDATE taska.issue_type_creations SET updated_at=now() WHERE tenant_id=:tenant AND id=:id").bind("id",item.id()).then();
    }
    private IssueTypeCreation read(Row r) {
        UUID project=r.get("project_id",UUID.class); String channel=r.get("channel",String.class);
        return new IssueTypeCreation(r.get("id",UUID.class),project,r.get("actor_user_id",UUID.class),r.get("request_id",String.class),
                r.get("node_id",String.class),r.get("command_hash",String.class),new ProjectIssueType(project,r.get("code",String.class),
                r.get("name",String.class),r.get("color",String.class),r.get("icon",String.class),r.get("shape",String.class),
                r.get("sort_order",Integer.class),null,null,r.get("generation",UUID.class)),r.get("desired_workflow_id",UUID.class),
                r.get("first_type_code",String.class),r.get("first_type_generation",UUID.class),r.get("resolved_workflow_id",UUID.class),
                r.get("state",String.class),new ActionMetadata(channel==null?null:ActionChannel.valueOf(channel),r.get("token_id",UUID.class)),
                r.get("error_status",String.class),r.get("error_message",String.class),mapper.readValue(r.get("error_json",String.class),new TypeReference<Map<String,String>>(){}));
    }
    private DatabaseClient.GenericExecuteSpec sql(String statement) {
        return db.sql(statement).bind("tenant", tenant);
    }
    private DatabaseClient.GenericExecuteSpec insertSql(String statement) {
        return authority.get().bind(sql(statement));
    }
}
