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
import ru.taska.domain.IssueTypeDeletion;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class IssueTypeDeletionRepository {
  private final DatabaseClient db;
  private final ObjectMapper mapper;
  private static final String SELECT = "SELECT *,status_mappings::text AS mapping_json,error_details::text AS error_json FROM taska.issue_type_deletions ";

  public Mono<IssueTypeDeletion> find(UUID project, UUID id) {
    return db.sql(SELECT+"WHERE project_id=:project AND id=:id").bind("project",project).bind("id",id)
        .map((row,metadata)->read(row)).one();
  }
  public Mono<IssueTypeDeletion> pending(UUID project) {
    return db.sql(SELECT+"WHERE project_id=:project AND state IN ('PENDING','CANCELLING')")
        .bind("project",project).map((row,metadata)->read(row)).one();
  }
  public Mono<IssueTypeDeletion> completed(UUID project, String source, String target, Map<String,String> mapping, UUID expected) {
    var spec=db.sql(SELECT+"WHERE project_id=:project AND source_code=:source AND state='COMPLETED' AND status_mappings=CAST(:mapping AS jsonb) "
        +(target==null?"AND target_code IS NULL ":"AND target_code=:target ")
        +(expected==null?"":"AND source_generation=:expected ")+"ORDER BY created_at DESC,id DESC LIMIT 1")
        .bind("project",project).bind("source",source).bind("mapping",mapper.writeValueAsString(mapping));
    if(target!=null)spec=spec.bind("target",target);
    if(expected!=null)spec=spec.bind("expected",expected);
    return spec.map((row,metadata)->read(row)).one();
  }
  public Mono<IssueTypeDeletion> completedRequest(UUID project,UUID actor,String source,String request) {
    return db.sql(SELECT+"WHERE project_id=:project AND actor_user_id=:actor AND source_code=:source AND request_id=:request AND target_code IS NULL AND state='COMPLETED' ORDER BY created_at DESC,id DESC LIMIT 1")
        .bind("project",project).bind("actor",actor).bind("source",source).bind("request",request).map((row,metadata)->read(row)).one();
  }
  public Mono<Boolean> hasOlderLifecycle(UUID project,String source,UUID generation) {
    return db.sql("SELECT EXISTS(SELECT 1 FROM taska.issue_type_deletions WHERE project_id=:project AND source_code=:source AND source_generation<>:generation AND state='COMPLETED') AS reused")
        .bind("project",project).bind("source",source).bind("generation",generation)
        .map((r,m)->Boolean.TRUE.equals(r.get("reused",Boolean.class))).one();
  }
  public Mono<IssueTypeDeletion> insert(IssueTypeDeletion item) {
    var sql=db.sql("""
        INSERT INTO taska.issue_type_deletions(id,project_id,source_code,source_generation,target_code,target_generation,
          actor_user_id,status_mappings,state,request_id,node_id,channel,token_id)
        VALUES(:id,:project,:source,:generation,:target,:targetGeneration,:actor,CAST(:mapping AS jsonb),'PENDING',:request,:node,:channel,:token)
        """).bind("id",item.id()).bind("project",item.projectId()).bind("source",item.source())
        .bind("generation",item.sourceGeneration()).bind("actor",item.actor())
        .bind("mapping",mapper.writeValueAsString(item.mappings())).bind("request",item.requestId()).bind("node",item.nodeId());
    sql=item.target()==null?sql.bindNull("target",String.class).bindNull("targetGeneration",UUID.class)
        :sql.bind("target",item.target()).bind("targetGeneration",item.targetGeneration());
    sql=item.attribution().channel()==null?sql.bindNull("channel",String.class):sql.bind("channel",item.attribution().channel().name());
    sql=item.attribution().tokenId()==null?sql.bindNull("token",UUID.class):sql.bind("token",item.attribution().tokenId());
    return sql.fetch().rowsUpdated().then(find(item.projectId(),item.id()));
  }
  public Mono<Long> fence(IssueTypeDeletion item) {
    var spec=db.sql("UPDATE taska.project_issue_types SET pending_operation_id=:id WHERE project_id=:project AND pending_operation_id IS NULL AND ((code=:source AND generation=:generation)"
        +(item.target()==null?"":" OR (code=:target AND generation=:targetGeneration)")+")")
        .bind("project",item.projectId()).bind("id",item.id()).bind("source",item.source()).bind("generation",item.sourceGeneration());
    if(item.target()!=null)spec=spec.bind("target",item.target()).bind("targetGeneration",item.targetGeneration());
    return spec.fetch().rowsUpdated();
  }
  public Mono<Long> deleteSource(IssueTypeDeletion item) {
    return db.sql("DELETE FROM taska.project_issue_types WHERE project_id=:project AND code=:source AND generation=:generation AND pending_operation_id=:id")
        .bind("project",item.projectId()).bind("source",item.source()).bind("generation",item.sourceGeneration())
        .bind("id",item.id()).fetch().rowsUpdated();
  }
  public Mono<Void> clearFence(IssueTypeDeletion item) {
    return db.sql("UPDATE taska.project_issue_types SET pending_operation_id=NULL WHERE project_id=:project AND pending_operation_id=:id")
        .bind("project",item.projectId()).bind("id",item.id()).fetch().rowsUpdated().then();
  }
  public Mono<IssueTypeDeletion> requestCancellation(IssueTypeDeletion item,ru.taska.exception.DomainException error) {
    return db.sql("UPDATE taska.issue_type_deletions SET state='CANCELLING',error_status=:status,error_message=:message,error_details=CAST(:details AS jsonb),updated_at=now() WHERE id=:id AND state='PENDING'")
        .bind("id",item.id()).bind("status",error.getStatus().name()).bind("message",error.getMessage())
        .bind("details",mapper.writeValueAsString(error.getDetails())).fetch().rowsUpdated().then(find(item.projectId(),item.id()));
  }
  public Mono<Void> terminal(IssueTypeDeletion item,String state) {
    return db.sql("UPDATE taska.issue_type_deletions SET state=:state,updated_at=now() WHERE id=:id AND state IN ('PENDING','CANCELLING')")
        .bind("id",item.id()).bind("state",state).fetch().rowsUpdated().then();
  }
  public Mono<Void> released(IssueTypeDeletion item) {
    return db.sql("UPDATE taska.issue_type_deletions SET reservation_released=true,updated_at=now() WHERE id=:id AND state IN ('COMPLETED','CANCELLED')")
        .bind("id",item.id()).fetch().rowsUpdated().then();
  }
  public Flux<IssueTypeDeletion> recoverable() {
    return db.sql(SELECT+"WHERE reservation_released=false ORDER BY updated_at,id LIMIT 20")
        .map((r,m)->read(r)).all();
  }
  public Mono<Void> touched(IssueTypeDeletion item) {
    return db.sql("UPDATE taska.issue_type_deletions SET updated_at=now() WHERE id=:id")
        .bind("id",item.id()).fetch().rowsUpdated().then();
  }
  private IssueTypeDeletion read(Row r) {
    String channel=r.get("channel",String.class);
    return new IssueTypeDeletion(r.get("id",UUID.class),r.get("project_id",UUID.class),r.get("source_code",String.class),
        r.get("source_generation",UUID.class),r.get("target_code",String.class),r.get("target_generation",UUID.class),
        r.get("actor_user_id",UUID.class),readMap(r.get("mapping_json",String.class)),r.get("state",String.class),
        r.get("request_id",String.class),r.get("node_id",String.class),new ActionMetadata(channel==null?null:ActionChannel.valueOf(channel),
        r.get("token_id",UUID.class)),r.get("error_status",String.class),r.get("error_message",String.class),
        readMap(r.get("error_json",String.class)),Boolean.TRUE.equals(r.get("reservation_released",Boolean.class)));
  }
  private Map<String,String> readMap(String json) { return mapper.readValue(json,new TypeReference<Map<String,String>>(){}); }
}
