package ru.taska.projectstorage;

import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.*;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.ObjectMapper;

/** SQL-only unit of work. No caller-supplied tenant, ambient/global fallback or remote waits. */
public final class TenantProjectStore {
  public record OperationAuthority(UUID grantId, long actorVersion, long membershipVersion, long fence) {}
  private final TenantTransactionExecutor transactions;
  private final ObjectMapper json;

  public TenantProjectStore(TenantTransactionExecutor transactions, ObjectMapper json) {
    this.transactions = transactions;
    this.json = json;
  }

  public <T> Mono<T> execute(Function<Session, Mono<T>> work) {
    return VerifiedTenantContext.current().flatMap(context -> transactions.execute(db ->
        work.apply(new Session(db, UUID.fromString(context.tenantId()), context))));
  }

  /** Instances and their publishers are usable only inside the originating transaction. */
  public final class Session {
    private final DatabaseClient db;
    private final UUID tenant;
    private final String actor;
    private final VerifiedTenantContext context;
    private Session(DatabaseClient db, UUID tenant, VerifiedTenantContext context) {
      this.db = db; this.tenant = tenant; this.context=context; this.actor = context.actorId();
    }

    public ProjectIssueTypeRepository types() { return new ProjectIssueTypeRepository(db, tenant); }
    public ProjectTypeOperationRepository operations() { return new ProjectTypeOperationRepository(db, tenant, () -> RecoveryStamp.from(context)); }
    public IssueTypeCreationRepository creations() { return new IssueTypeCreationRepository(db, tenant, () -> RecoveryStamp.from(context), json); }
    public IssueTypeDeletionRepository deletions() { return new IssueTypeDeletionRepository(db, tenant, () -> RecoveryStamp.from(context), json); }

    public Mono<OperationAuthority> operationAuthority(UUID project, UUID id) {
      return sql("""
          SELECT grant_id,actor_auth_version,membership_version,operation_fence
          FROM taska.project_type_operations WHERE tenant_id=:tenant AND project_id=:project AND id=:id
          """).bind("project",project).bind("id",id).map((r,m)->new OperationAuthority(r.get("grant_id",UUID.class),
              r.get("actor_auth_version",Long.class),r.get("membership_version",Long.class),r.get("operation_fence",Long.class))).one();
    }

    public Mono<Project> insertProject(UUID id, String key, String name, UUID creator) {
      return sql("""
          INSERT INTO taska.projects(tenant_id,id,project_key,name,created_by)
          VALUES(:tenant,:id,:key,:name,:creator) RETURNING *
          """).bind("id", id).bind("key", key).bind("name", name).bind("creator", creator)
          .map((r,m) -> project(r)).one();
    }

    public Mono<Project> find(UUID id) {
      return sql("SELECT * FROM taska.projects WHERE tenant_id=:tenant AND id=:id")
          .bind("id", id).map((r,m) -> project(r)).one();
    }

    public Mono<Project> lock(UUID id) {
      return sql("SELECT * FROM taska.projects WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
          .bind("id", id).map((r,m) -> project(r)).one();
    }

    public Mono<Project> findByKey(String key) {
      return sql("SELECT * FROM taska.projects WHERE tenant_id=:tenant AND project_key=:key")
          .bind("key", key).map((r,m) -> project(r)).one();
    }

    public Flux<Project> listForUser(UUID user) {
      return sql("""
          SELECT p.* FROM taska.projects p
          JOIN taska.project_members pm ON pm.tenant_id=p.tenant_id AND pm.project_id=p.id
          WHERE p.tenant_id=:tenant AND pm.tenant_id=:tenant AND pm.user_id=:user AND p.archived_at IS NULL
          ORDER BY p.created_at,p.id
          """).bind("user", user).map((r,m) -> project(r)).all();
    }

    public Flux<ProjectInfo> infoBatch(Collection<UUID> ids) {
      if (ids.isEmpty()) return Flux.empty();
      return sql("SELECT id,project_key,name FROM taska.projects WHERE tenant_id=:tenant AND id IN (:ids)")
          .bind("ids", ids).map((r,m) -> new ProjectInfo(r.get("id",UUID.class),
              r.get("project_key",String.class),r.get("name",String.class))).all();
    }

    /** Includes archived projects. Archive does not release the lifetime project quota. */
    public Mono<Long> countProjects() {
      return sql("SELECT count(*) n FROM taska.projects WHERE tenant_id=:tenant")
          .map((r,m) -> r.get("n",Long.class)).one();
    }

    public Mono<Project> update(Project value) {
      return nullable(nullable(sql("""
          UPDATE taska.projects SET name=:name,description=:description,color=:color,
            version=version+1,updated_at=now()
          WHERE tenant_id=:tenant AND id=:id AND version=:version AND archived_at IS NULL RETURNING *
          """), "description", value.getDescription()), "color", value.getColor())
          .bind("name", value.getName()).bind("id", value.getId()).bind("version",value.getVersion())
          .map((r,m) -> project(r)).one()
          .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Project changed or unavailable")));
    }

    public Mono<Void> addMember(UUID project, UUID user, ProjectRole role, UUID addedBy) {
      return sql("""
          INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by)
          VALUES(:tenant,:project,:user,:role,:by)
          """).bind("project",project).bind("user",user).bind("role",role.name()).bind("by",addedBy).then();
    }

    public Flux<ProjectMember> members(UUID project) {
      return sql("SELECT * FROM taska.project_members WHERE tenant_id=:tenant AND project_id=:project ORDER BY user_id")
          .bind("project",project).map((r,m) -> ProjectMember.builder().projectId(r.get("project_id",UUID.class))
              .userId(r.get("user_id",UUID.class)).role(ProjectRole.valueOf(r.get("role",String.class)))
              .addedBy(r.get("added_by",UUID.class)).addedAt(r.get("added_at",OffsetDateTime.class).toInstant()).build()).all();
    }

    public Mono<Long> changeRole(UUID project, UUID user, ProjectRole role) {
      return sql("UPDATE taska.project_members SET role=:role WHERE tenant_id=:tenant AND project_id=:project AND user_id=:user")
          .bind("project",project).bind("user",user).bind("role",role.name()).fetch().rowsUpdated();
    }

    public Mono<Long> removeMember(UUID project, UUID user) {
      return sql("DELETE FROM taska.project_members WHERE tenant_id=:tenant AND project_id=:project AND user_id=:user")
          .bind("project",project).bind("user",user).fetch().rowsUpdated();
    }

    public Mono<Void> settings(UUID project, String settings, UUID updatedBy) {
      return sql("""
          INSERT INTO taska.project_settings(tenant_id,project_id,settings,updated_by)
          VALUES(:tenant,:project,CAST(:settings AS jsonb),:by)
          ON CONFLICT(tenant_id,project_id) DO UPDATE SET settings=EXCLUDED.settings,
            updated_at=now(),updated_by=EXCLUDED.updated_by
          """).bind("project",project).bind("settings",settings).bind("by",updatedBy).then();
    }

    public Mono<String> settings(UUID project) {
      return sql("SELECT settings::text value FROM taska.project_settings WHERE tenant_id=:tenant AND project_id=:project")
          .bind("project",project).map((r,m)->r.get("value",String.class)).one();
    }

    /** Storage composition retaining the existing archive ACL, lock order, idempotency and outbox. */
    public Mono<Project> archive(UUID id) {
      if (actor == null) return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Human actor required"));
      UUID user = UUID.fromString(actor);
      return lock(id).switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found")))
          .flatMap(current -> types().lockActorRole(id,user)
              .filter(role -> role == ProjectRole.ADMIN)
              .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Project ADMIN required")))
              .flatMap(role -> {
                if (current.getArchivedAt()!=null) return Mono.just(current);
                return sql("""
                    UPDATE taska.projects SET archived_at=now(),updated_at=now(),version=version+1
                    WHERE tenant_id=:tenant AND id=:id RETURNING *
                    """).bind("id",id).map((r,m)->project(r)).one().flatMap(saved -> members(id).collectList()
                        .flatMap(all -> appendEvent(id,"ProjectArchived",json.writeValueAsString(
                            new ru.taska.event.payload.projectService.ProjectArchivedPayload(id,saved.getProjectKey(),
                              saved.getName(),user,saved.getArchivedAt(),all.stream().map(ProjectMember::getUserId).toList())))
                            .thenReturn(saved)));
              }));
    }

    /** Tenant outbox only. Cross-tenant dispatch/lease discovery belongs to TAS-318/TAS-489. */
    public Mono<Void> appendEvent(UUID project, String type, String payload) {
      return sql("""
          INSERT INTO taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload)
          SELECT :tenant,'project',id,:type,CAST(:payload AS jsonb)
          FROM taska.projects WHERE tenant_id=:tenant AND id=:project
          """).bind("project",project).bind("type",type).bind("payload",payload).fetch().rowsUpdated()
          .flatMap(n -> n==1 ? Mono.empty() : Mono.error(new DomainException(DomainStatus.NOT_FOUND,"Project not found")));
    }

    public Flux<UUID> eventIds(UUID project) {
      return sql("SELECT id FROM taska.outbox_events WHERE tenant_id=:tenant AND aggregate_id=:project ORDER BY created_at,id")
          .bind("project",project).map((r,m)->r.get("id",UUID.class)).all();
    }

    private DatabaseClient.GenericExecuteSpec sql(String statement) { return db.sql(statement).bind("tenant",tenant); }
  }

  private static DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec sql, String name, String value) {
    return value==null ? sql.bindNull(name,String.class) : sql.bind(name,value);
  }

  private static Project project(Row r) {
    var archived=r.get("archived_at",OffsetDateTime.class);
    return Project.builder().id(r.get("id",UUID.class)).projectKey(r.get("project_key",String.class))
        .name(r.get("name",String.class)).createdBy(r.get("created_by",UUID.class))
        .createdAt(r.get("created_at",OffsetDateTime.class).toInstant())
        .updatedAt(r.get("updated_at",OffsetDateTime.class).toInstant())
        .archivedAt(archived==null?null:archived.toInstant()).description(r.get("description",String.class))
        .color(r.get("color",String.class)).version(r.get("version",Integer.class)).build();
  }
}
