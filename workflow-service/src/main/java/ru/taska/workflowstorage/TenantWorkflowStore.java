package ru.taska.workflowstorage;

import io.r2dbc.spi.Row;
import java.util.*;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.ObjectMapper;

/** SQL-only persistence boundary. TAS-342 supplies current RPC/project/capability authorization. */
public final class TenantWorkflowStore {
  public record Workflow(UUID id, UUID project, String name, int version, boolean active) {}
  public record Status(UUID id, UUID workflow, String key, String name, String category, int order) {}
  public record Transition(UUID id, UUID workflow, UUID from, UUID to, String name, boolean hidden, int order) {}
  public record Rule(UUID id, UUID transition, String type, String config, String errorCode, String errorMessage, int order) {}
  public record Binding(UUID id, UUID project, String type, UUID generation, UUID workflow) {}
  public record TemplateCopy(UUID id, UUID templateId, String code, long version, String name, int schema, String graph) {}
  public record Pin(UUID operation, UUID project, UUID workflow, List<String> keys, boolean released) {}
  public record Receipt(UUID operation, UUID project, String state, UUID workflow, String code, UUID generation) {}
  private final TenantTransactionExecutor transactions;
  private final ObjectMapper json;

  public TenantWorkflowStore(TenantTransactionExecutor transactions,ObjectMapper json) {
    this.transactions=transactions; this.json=json;
  }

  public <T> Mono<T> execute(Function<Session,Mono<T>> work) {
    return VerifiedTenantContext.current().flatMap(c -> transactions.execute(db ->
        work.apply(new Session(db,UUID.fromString(c.tenantId()),c))));
  }

  public final class Session {
    private final DatabaseClient db;
    private final UUID tenant;
    private final VerifiedTenantContext context;
    private Session(DatabaseClient db,UUID tenant,VerifiedTenantContext context) {
      this.db=db;this.tenant=tenant;this.context=context;
    }
    public WorkflowBindingChangeRepository changes() {
      return new WorkflowBindingChangeRepository(db,tenant,()->RecoveryStamp.from(context),json);
    }

    /**
     * Operation receipt prevents a retry with altered template input from creating a second copy.
     */
    public Mono<TemplateCopy> provision(UUID operation, UUID template) {
      return sql("SELECT"
                     + " pg_advisory_xact_lock(hashtextextended(:tenant::text||':provision:'||:key,0))")
          .bind("key", operation.toString())
          .fetch()
          .all()
          .then(
              sql("SELECT template_id,template_copy_id FROM taska.workflow_provisioning_receipts"
                      + " WHERE tenant_id=:tenant AND operation_id=:operation")
                  .bind("operation", operation)
                  .fetch()
                  .one()
                  .flatMap(
                      row -> {
                        if (!template.equals(row.get("template_id")))
                          return Mono.error(
                              new DomainException(
                                  DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                        return copy((UUID) row.get("template_copy_id"));
                      })
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              provisionTemplate(template)
                                  .flatMap(
                                      copy ->
                                          sql("INSERT INTO"
                                                  + " taska.workflow_provisioning_receipts(tenant_id,operation_id,template_id,template_copy_id)"
                                                  + " VALUES(:tenant,:operation,:template,:copy)")
                                              .bind("operation", operation)
                                              .bind("template", template)
                                              .bind("copy", copy.id())
                                              .then()
                                              .thenReturn(copy)))));
    }

    /** One immutable private copy per tenant/published version; concurrent repeats share its UUID. */
    public Mono<TemplateCopy> provisionTemplate(UUID template) {
      return db.sql("SELECT graph::text graph,graph_schema_version FROM taska.workflow_templates WHERE id=:id AND published_at IS NOT NULL")
          .bind("id",template).map((r,m)->decode(r.get("graph",String.class),r.get("graph_schema_version",Integer.class)))
          .one().switchIfEmpty(Mono.error(missing())).flatMap(graph ->
              sql("""
                  INSERT INTO taska.tenant_workflow_templates(tenant_id,template_id,code,version,name,graph_schema_version,graph)
                  SELECT :tenant,id,code,version,name,graph_schema_version,graph FROM taska.workflow_templates
                  WHERE id=:template AND published_at IS NOT NULL
                  ON CONFLICT DO NOTHING
                  """).bind("template",template).then().then(copyForTemplate(template))
                  .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Template copy identity conflict"))));
    }

    public Mono<TemplateCopy> copy(UUID id) {
      return sql("SELECT *,graph::text graph_text FROM taska.tenant_workflow_templates WHERE tenant_id=:tenant AND id=:id")
          .bind("id",id).map((r,m)->copyRow(r)).one();
    }
    private Mono<TemplateCopy> copyForTemplate(UUID id) {
      return sql("SELECT *,graph::text graph_text FROM taska.tenant_workflow_templates WHERE tenant_id=:tenant AND template_id=:id")
          .bind("id",id).map((r,m)->copyRow(r)).one();
    }
    public Flux<TemplateCopy> copies() {
      return sql("SELECT *,graph::text graph_text FROM taska.tenant_workflow_templates WHERE tenant_id=:tenant ORDER BY code,version")
          .map((r,m)->copyRow(r)).all();
    }

    /** A real project identity is required; company provisioning never invents a zero project. */
    public Mono<Workflow> instantiate(UUID templateCopy,UUID project) {
      requireProject(project);
      return copy(templateCopy).switchIfEmpty(Mono.error(missing())).flatMap(template -> {
        var graph=decode(template.graph(),template.schema()); UUID candidate=UUID.randomUUID();
        return sql("""
            INSERT INTO taska.workflow_template_instances(tenant_id,project_id,template_copy_id,workflow_id)
            VALUES(:tenant,:project,:copy,:workflow)
            ON CONFLICT(tenant_id,project_id,template_copy_id) DO NOTHING
            """).bind("project",project).bind("copy",templateCopy).bind("workflow",candidate).fetch().rowsUpdated()
            .flatMap(n -> n==1 ? materialize(candidate,project,template.name(),graph)
                : sql("SELECT workflow_id FROM taska.workflow_template_instances WHERE tenant_id=:tenant AND project_id=:project AND template_copy_id=:copy")
                    .bind("project",project).bind("copy",templateCopy).map((r,m)->r.get("workflow_id",UUID.class)).one()
                    .flatMap(this::find).switchIfEmpty(Mono.error(missing())));
      });
    }

    private Mono<Workflow> materialize(UUID id,UUID project,String name,TemplateGraph graph) {
      var ids=new HashMap<String,UUID>();graph.statuses().forEach(n->ids.put(n.key(),UUID.randomUUID()));
      return insertWorkflow(id,project,name).thenMany(Flux.fromIterable(graph.statuses()).concatMap(n ->
          insertStatus(ids.get(n.key()),id,n.key(),n.name(),n.category(),n.sortOrder())))
          .then(Mono.defer(()->Flux.fromIterable(graph.transitions()).concatMap(edge ->
              insertTransition(UUID.randomUUID(),id,ids.get(edge.from()),ids.get(edge.to()),edge.name(),edge.hidden(),edge.sortOrder())
                  .flatMap(t -> Flux.fromIterable(edge.rules()).concatMap(rule -> addRule(t.id(),rule.type(),
                      json.writeValueAsString(rule.config()),rule.errorCode(),rule.errorMessage(),rule.sortOrder())).then()))
              .then())).then(find(id));
    }

    public Mono<Workflow> insertWorkflow(UUID id,UUID project,String name) {
      return insertWorkflow(id,project,name,true);
    }
    public Mono<Workflow> insertWorkflow(UUID id,UUID project,String name,boolean active) {
      requireProject(project);
      return sql("INSERT INTO taska.workflows(tenant_id,id,owner_project_id,name,is_active) VALUES(:tenant,:id,:project,:name,:active) RETURNING *")
          .bind("id",id).bind("project",project).bind("name",name).bind("active",active).map((r,m)->workflow(r)).one();
    }
    /** Cancellation of an unpublished CREATE graph retains its terminal journal as provenance. */
    public Mono<Long> discardPrepared(UUID project,UUID id) {
      return sql("DELETE FROM taska.workflows WHERE tenant_id=:tenant AND owner_project_id=:project AND id=:id AND is_active=false")
          .bind("project",project).bind("id",id).fetch().rowsUpdated();
    }
    public Mono<Workflow> find(UUID id) {
      return sql("SELECT * FROM taska.workflows WHERE tenant_id=:tenant AND id=:id")
          .bind("id",id).map((r,m)->workflow(r)).one();
    }
    public Mono<Workflow> lock(UUID id) {
      return sql("SELECT * FROM taska.workflows WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
          .bind("id",id).map((r,m)->workflow(r)).one();
    }
    public Flux<Workflow> list(UUID project) {
      return sql("SELECT * FROM taska.workflows WHERE tenant_id=:tenant AND owner_project_id=:project AND is_active ORDER BY created_at,id")
          .bind("project",project).map((r,m)->workflow(r)).all();
    }
    public Mono<Workflow> rename(UUID id,int version,String name) {
      return sql("UPDATE taska.workflows SET name=:name,version=version+1,updated_at=now() WHERE tenant_id=:tenant AND id=:id AND version=:version RETURNING *")
          .bind("id",id).bind("version",version).bind("name",name).map((r,m)->workflow(r)).one()
          .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow changed or unavailable")));
    }
    public Mono<Status> addStatus(UUID workflow,String key,String name,String category,int order) {
      return insertStatus(UUID.randomUUID(),workflow,key,name,category,order);
    }
    private Mono<Status> insertStatus(UUID id,UUID workflow,String key,String name,String category,int order) {
      return sql("INSERT INTO taska.statuses(tenant_id,id,workflow_id,status_key,name,category,sort_order) VALUES(:tenant,:id,:workflow,:key,:name,:category,:order) RETURNING *")
          .bind("id",id).bind("workflow",workflow).bind("key",key).bind("name",name).bind("category",category).bind("order",order)
          .map((r,m)->status(r)).one();
    }
    public Flux<Status> statuses(UUID workflow) {
      return sql("SELECT * FROM taska.statuses WHERE tenant_id=:tenant AND workflow_id=:workflow ORDER BY sort_order,status_key")
          .bind("workflow",workflow).map((r,m)->status(r)).all();
    }
    public Mono<Long> renameStatus(UUID workflow,UUID id,String name) {
      return lock(workflow).switchIfEmpty(Mono.error(missing())).flatMap(w ->
          sql("UPDATE taska.statuses SET name=:name,updated_at=now() WHERE tenant_id=:tenant AND workflow_id=:workflow AND id=:id")
              .bind("workflow",workflow).bind("id",id).bind("name",name).fetch().rowsUpdated());
    }
    public Mono<Transition> addTransition(UUID workflow,UUID from,UUID to,String name,boolean hidden,int order) {
      return insertTransition(UUID.randomUUID(),workflow,from,to,name,hidden,order);
    }
    private Mono<Transition> insertTransition(UUID id,UUID workflow,UUID from,UUID to,String name,boolean hidden,int order) {
      return sql("INSERT INTO taska.transitions(tenant_id,id,workflow_id,from_status_id,to_status_id,name,is_hidden,sort_order) VALUES(:tenant,:id,:workflow,:from,:to,:name,:hidden,:order) RETURNING *")
          .bind("id",id).bind("workflow",workflow).bind("from",from).bind("to",to).bind("name",name).bind("hidden",hidden).bind("order",order)
          .map((r,m)->transition(r)).one();
    }
    public Flux<Transition> transitions(UUID workflow) {
      return sql("SELECT * FROM taska.transitions WHERE tenant_id=:tenant AND workflow_id=:workflow ORDER BY sort_order,id")
          .bind("workflow",workflow).map((r,m)->transition(r)).all();
    }
    public Mono<Rule> addRule(UUID transition,String type,String config,String errorCode,String errorMessage,int order) {
      return nullable(nullable(sql("""
          INSERT INTO taska.validator_rules(tenant_id,transition_id,rule_type,config,error_code,error_message,sort_order)
          VALUES(:tenant,:transition,:type,CAST(:config AS jsonb),:code,:message,:order)
          RETURNING *,config::text config_text
          """),"code",errorCode),"message",errorMessage).bind("transition",transition).bind("type",type)
          .bind("config",config).bind("order",order).map((r,m)->rule(r)).one();
    }
    public Flux<Rule> rules(UUID workflow) {
      return sql("""
          SELECT r.*,r.config::text config_text FROM taska.validator_rules r
          JOIN taska.transitions t ON t.tenant_id=r.tenant_id AND t.id=r.transition_id
          WHERE r.tenant_id=:tenant AND t.tenant_id=:tenant AND t.workflow_id=:workflow ORDER BY t.sort_order,r.sort_order,r.id
          """).bind("workflow",workflow).map((r,m)->rule(r)).all();
    }

    public Mono<Void> bind(UUID project,String type,UUID generation,UUID workflow) {
      requireProject(project);
      return sql("""
          INSERT INTO taska.workflow_bindings(tenant_id,project_id,issue_type,issue_type_generation,workflow_id)
          SELECT :tenant,:project,:type,:generation,id FROM taska.workflows
          WHERE tenant_id=:tenant AND owner_project_id=:project AND id=:workflow AND is_active
          ON CONFLICT(tenant_id,project_id,issue_type) DO UPDATE SET workflow_id=EXCLUDED.workflow_id,
            issue_type_generation=EXCLUDED.issue_type_generation,updated_at=now()
          """).bind("project",project).bind("type",type).bind("generation",generation).bind("workflow",workflow)
          .fetch().rowsUpdated().flatMap(n->n==1?Mono.empty():Mono.error(missing()));
    }
    /** Exact current generation only. Missing binding stays missing; there is no default fallback. */
    public Mono<Workflow> resolve(UUID project,String type,UUID generation) {
      return sql("""
          SELECT w.* FROM taska.workflow_bindings b JOIN taska.workflows w
            ON w.tenant_id=b.tenant_id AND w.id=b.workflow_id AND w.owner_project_id=b.project_id
          WHERE b.tenant_id=:tenant AND w.tenant_id=:tenant AND b.project_id=:project
            AND b.issue_type=:type AND b.issue_type_generation=:generation AND w.is_active
          """).bind("project",project).bind("type",type).bind("generation",generation).map((r,m)->workflow(r)).one();
    }
    public Flux<Binding> bindings(Collection<UUID> projects) {
      if(projects.isEmpty())return Flux.empty();
      return sql("SELECT * FROM taska.workflow_bindings WHERE tenant_id=:tenant AND project_id IN (:projects) ORDER BY project_id,issue_type")
          .bind("projects",projects).map((r,m)->new Binding(r.get("id",UUID.class),r.get("project_id",UUID.class),
              r.get("issue_type",String.class),r.get("issue_type_generation",UUID.class),r.get("workflow_id",UUID.class))).all();
    }
    public Mono<Long> unbind(UUID project,String type,UUID generation) {
      return sql("DELETE FROM taska.workflow_bindings WHERE tenant_id=:tenant AND project_id=:project AND issue_type=:type AND issue_type_generation=:generation")
          .bind("project",project).bind("type",type).bind("generation",generation).fetch().rowsUpdated();
    }

    public Mono<Pin> pin(UUID project,UUID operation,UUID workflow) {
      var stamp=RecoveryStamp.from(context);
      return lock(workflow).filter(w->w.project().equals(project)).switchIfEmpty(Mono.error(missing()))
          .flatMap(w->stamp.bind(sql("""
              INSERT INTO taska.issue_type_move_workflow_pins(tenant_id,operation_id,project_id,workflow_id,valid_status_keys,grant_id,actor_auth_version,membership_version)
              SELECT :tenant,:operation,:project,:workflow,array_agg(status_key ORDER BY sort_order,status_key),:grant,:authVersion,:memberVersion
              FROM taska.statuses WHERE tenant_id=:tenant AND workflow_id=:workflow HAVING count(*)>0
              ON CONFLICT(tenant_id,operation_id) DO NOTHING
              """)).bind("operation",operation).bind("project",project).bind("workflow",workflow).then())
          .then(pin(project,operation)).filter(p->p.workflow().equals(workflow)&&!p.released())
          .switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow pin identity changed or closed")));
    }
    public Mono<Pin> pin(UUID project,UUID operation) {
      return sql("SELECT * FROM taska.issue_type_move_workflow_pins WHERE tenant_id=:tenant AND project_id=:project AND operation_id=:operation")
          .bind("project",project).bind("operation",operation).map((r,m)->new Pin(operation,project,r.get("workflow_id",UUID.class),
              Arrays.asList(r.get("valid_status_keys",String[].class)),r.get("released",Boolean.class))).one();
    }
    public Mono<Void> releasePin(UUID project,UUID operation) {
      return sql("UPDATE taska.issue_type_move_workflow_pins SET released=true WHERE tenant_id=:tenant AND project_id=:project AND operation_id=:operation")
          .bind("project",project).bind("operation",operation).then();
    }
    public Mono<Receipt> receipt(UUID project,UUID operation) {
      return sql("SELECT * FROM taska.issue_type_creation_workflow_receipts WHERE tenant_id=:tenant AND project_id=:project AND operation_id=:operation")
          .bind("project",project).bind("operation",operation).map((r,m)->new Receipt(operation,project,r.get("state",String.class),
              r.get("workflow_id",UUID.class),r.get("code",String.class),r.get("generation",UUID.class))).one();
    }
    public Mono<Receipt> recordReceipt(Receipt value) {
      if(!Set.of("APPLIED","CANCELLED").contains(value.state()))return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid receipt state"));
      requireProject(value.project());
      return receipt(value.project(),value.operation()).switchIfEmpty(Mono.defer(()->{
        Mono<Void> graph=value.workflow()==null?Mono.empty():lock(value.workflow()).filter(w->w.project().equals(value.project()))
            .switchIfEmpty(Mono.error(missing())).then();
        return graph.then(Mono.defer(()->insertReceipt(value)));
      })).filter(value::equals).switchIfEmpty(Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow receipt identity mismatch")));
    }
    private Mono<Receipt> insertReceipt(Receipt value) {
      var query=RecoveryStamp.from(context).bind(sql("""
          INSERT INTO taska.issue_type_creation_workflow_receipts(tenant_id,operation_id,project_id,state,workflow_id,code,generation,grant_id,actor_auth_version,membership_version)
          VALUES(:tenant,:operation,:project,:state,:workflow,:code,:generation,:grant,:authVersion,:memberVersion)
          ON CONFLICT(tenant_id,operation_id) DO NOTHING
          """)).bind("operation",value.operation()).bind("project",value.project()).bind("state",value.state());
      query=value.workflow()==null?query.bindNull("workflow",UUID.class):query.bind("workflow",value.workflow());
      query=value.generation()==null?query.bindNull("generation",UUID.class):query.bind("generation",value.generation());
      query=nullable(query,"code",value.code());
      return query.then().then(receipt(value.project(),value.operation()));
    }
    private DatabaseClient.GenericExecuteSpec sql(String text) {return db.sql(text).bind("tenant",tenant);}
  }

  private TemplateGraph decode(String text,int version) {
    if(version!=1)throw TemplateGraph.invalid();
    try{return json.readValue(text,TemplateGraph.class).validated();}
    catch(DomainException invalid){throw invalid;}
    catch(RuntimeException invalid){throw TemplateGraph.invalid();}
  }
  private static void requireProject(UUID project) {
    if(project==null || project.equals(new UUID(0,0)))throw new DomainException(DomainStatus.INVALID_ARGUMENT,"A real project identity is required");
  }
  private static DomainException missing(){return new DomainException(DomainStatus.NOT_FOUND,"Workflow or template not found");}
  private static DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec sql,String key,String value){return value==null?sql.bindNull(key,String.class):sql.bind(key,value);}
  private static Workflow workflow(Row r){return new Workflow(r.get("id",UUID.class),r.get("owner_project_id",UUID.class),r.get("name",String.class),r.get("version",Integer.class),r.get("is_active",Boolean.class));}
  private static Status status(Row r){return new Status(r.get("id",UUID.class),r.get("workflow_id",UUID.class),r.get("status_key",String.class),r.get("name",String.class),r.get("category",String.class),r.get("sort_order",Integer.class));}
  private static Transition transition(Row r){return new Transition(r.get("id",UUID.class),r.get("workflow_id",UUID.class),r.get("from_status_id",UUID.class),r.get("to_status_id",UUID.class),r.get("name",String.class),r.get("is_hidden",Boolean.class),r.get("sort_order",Integer.class));}
  private static Rule rule(Row r){return new Rule(r.get("id",UUID.class),r.get("transition_id",UUID.class),r.get("rule_type",String.class),r.get("config_text",String.class),r.get("error_code",String.class),r.get("error_message",String.class),r.get("sort_order",Integer.class));}
  private static TemplateCopy copyRow(Row r){return new TemplateCopy(r.get("id",UUID.class),r.get("template_id",UUID.class),r.get("code",String.class),r.get("version",Long.class),r.get("name",String.class),r.get("graph_schema_version",Integer.class),r.get("graph_text",String.class));}
}
