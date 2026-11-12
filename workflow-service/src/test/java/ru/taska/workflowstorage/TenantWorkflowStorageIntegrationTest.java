package ru.taska.workflowstorage;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.security.tenant.WorkflowTestContext.as;

import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import ru.taska.WorkflowServiceApplication;
import ru.taska.domain.*;
import ru.taska.exception.*;
import ru.taska.workflowbootstrap.TenantStorageConfiguration;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@Timeout(90)
class TenantWorkflowStorageIntegrationTest extends TenantWorkflowFixture {
  @Test
  void provisioningReceiptIsAtomicTenantScopedAndRejectsChangedTemplate() {
    UUID operation = UUID.randomUUID();
    var copies =
        Flux.range(0, 8)
            .flatMap(i -> as(A, ACTOR, store.execute(s -> s.provision(operation, DEFAULT))), 8)
            .collectList()
            .block(WAIT);
    assertEquals(1, copies.stream().map(TenantWorkflowStore.TemplateCopy::id).distinct().count());
    assertEquals(
        copies.get(0),
        as(
                A,
                ACTOR,
                new TenantWorkflowStore(new TenantTransactionExecutor(pool), json)
                    .execute(s -> s.provision(operation, DEFAULT)))
            .block(WAIT));
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, store.execute(s -> s.provision(operation, UUID.randomUUID())))
                .block(WAIT));
    var other = as(B, ACTOR, store.execute(s -> s.provision(operation, DEFAULT))).block(WAIT);
    assertNotEquals(copies.get(0).id(), other.id());
    assertEquals(
        2L,
        operator
            .sql("SELECT count(*) n FROM taska.workflow_provisioning_receipts")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.workflow_provisioning_receipts")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test void copyWaitsForUncommittedWinnerAndReadsItsCommittedIdentity() throws Exception {
    Sinks.Empty<Void> held=Sinks.empty(),release=Sinks.empty();
    var winner=as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT).flatMap(c->{held.tryEmitEmpty();return release.asMono().thenReturn(c);}))).toFuture();
    held.asMono().block(WAIT);
    var loser=as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT))).toFuture();
    try{awaitBlockedInsert("tenant_workflow_templates",loser);}finally{release.tryEmitEmpty();}
    assertEquals(winner.get(20,java.util.concurrent.TimeUnit.SECONDS).id(),loser.get(20,java.util.concurrent.TimeUnit.SECONDS).id());
    assertEquals(1,count("tenant_workflow_templates"));
  }

  @Test void copyWaitsForRollbackThenBecomesTheOnlyWinner() throws Exception {
    Sinks.One<UUID> held=Sinks.one();Sinks.Empty<Void> release=Sinks.empty();
    var winner=as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT).flatMap(c->{held.tryEmitValue(c.id());
      return release.asMono().then(Mono.<TenantWorkflowStore.TemplateCopy>error(new IllegalStateException("fixture rollback")));}))).toFuture();
    UUID rolledBack=held.asMono().block(WAIT);
    var loser=as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT))).toFuture();
    try{awaitBlockedInsert("tenant_workflow_templates",loser);}finally{release.tryEmitEmpty();}
    assertThrows(java.util.concurrent.ExecutionException.class,()->winner.get(20,java.util.concurrent.TimeUnit.SECONDS));
    assertNotEquals(rolledBack,loser.get(20,java.util.concurrent.TimeUnit.SECONDS).id());assertEquals(1,count("tenant_workflow_templates"));
  }

  @Test void instanceWaitsForUncommittedGraphAndReturnsOnlyThatCompleteGraph() throws Exception {
    var c=copy(A);UUID project=UUID.randomUUID();Sinks.Empty<Void> held=Sinks.empty(),release=Sinks.empty();
    var winner=as(A,ACTOR,store.execute(s->s.instantiate(c.id(),project).flatMap(w->{held.tryEmitEmpty();return release.asMono().thenReturn(w);}))).toFuture();
    held.asMono().block(WAIT);
    var loser=as(A,ACTOR,store.execute(s->s.instantiate(c.id(),project))).toFuture();
    try{awaitBlockedInsert("workflow_template_instances",loser);}finally{release.tryEmitEmpty();}
    assertEquals(winner.get(20,java.util.concurrent.TimeUnit.SECONDS).id(),loser.get(20,java.util.concurrent.TimeUnit.SECONDS).id());
    assertEquals(1,count("workflows"));assertEquals(1,count("workflow_template_instances"));assertEquals(3,count("statuses"));assertEquals(6,count("transitions"));
  }

  @Test void parallelProvisioningProducesOnePrivateCopyPerTenantVersion() {
    var copies=Flux.range(0,10).flatMap(i->as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT))),10).collectList().block(WAIT);
    assertEquals(1,copies.stream().map(c->c.id()).distinct().count());
    var b=copy(B);assertNotEquals(copies.get(0).id(),b.id());
    assertEquals(copies.get(0).graph(),b.graph());assertEquals(2,count("tenant_workflow_templates"));
    assertEquals(0,count("workflows"));assertEquals(1,count("workflow_templates"));
  }

  @Test void parallelInstantiationHasStableNewGraphUuidsAndNoOrphans() {
    UUID project=UUID.randomUUID();var a=copy(A);
    var values=Flux.range(0,10).flatMap(i->as(A,ACTOR,store.execute(s->s.instantiate(a.id(),project))),10).collectList().block(WAIT);
    assertEquals(1,values.stream().map(w->w.id()).distinct().count());assertNotEquals(DEFAULT,values.get(0).id());
    assertEquals(1,count("workflow_template_instances"));assertEquals(1,count("workflows"));
    assertEquals(3,count("statuses"));assertEquals(6,count("transitions"));
    var before=as(A,ACTOR,store.execute(s->s.statuses(values.get(0).id()).collectList())).block(WAIT);
    var retry=as(A,ACTOR,store.execute(s->s.instantiate(a.id(),project))).block(WAIT);
    assertEquals(values.get(0).id(),retry.id());assertEquals(before,as(A,ACTOR,store.execute(s->s.statuses(retry.id()).collectList())).block(WAIT));
    var b=graph(B,project);var bStatuses=as(B,ACTOR,store.execute(s->s.statuses(b.id()).collectList())).block(WAIT);
    assertTrue(Collections.disjoint(before.stream().map(s->s.id()).toList(),bStatuses.stream().map(s->s.id()).toList()));
    assertNotEquals(values.get(0).id(),b.id());
  }

  @Test void editingAHasNoEffectOnTemplateBOrAnotherProjectAndNamesCanRepeat() {
    UUID project=UUID.randomUUID();var a=graph(A,project);var b=graph(B,project);var other=graph(A,UUID.randomUUID());
    var status=as(A,ACTOR,store.execute(s->s.statuses(a.id()).next())).block(WAIT);
    as(A,ACTOR,store.execute(s->s.rename(a.id(),1,"Changed").then(s.renameStatus(a.id(),status.id(),"Changed status")))).block(WAIT);
    assertEquals("Default workflow",as(B,ACTOR,store.execute(s->s.find(b.id()))).block(WAIT).name());
    assertEquals("Default workflow",as(A,ACTOR,store.execute(s->s.find(other.id()))).block(WAIT).name());
    assertEquals("To Do",as(B,ACTOR,store.execute(s->s.statuses(b.id()).next())).block(WAIT).name());
    assertEquals(seed,operator.sql("SELECT graph::text g FROM taska.workflow_templates WHERE id=:id").bind("id",DEFAULT).map((r,m)->r.get("g",String.class)).one().block(WAIT));
    assertEquals(3,count("workflows"));
  }

  @Test void explicitBindingsNeverFallBackToGlobalOrForeignDefault() {
    UUID project=UUID.randomUUID(),generation=UUID.randomUUID();var a=graph(A,project);var b=graph(B,project);
    as(A,ACTOR,store.execute(s->s.bind(project,"TASK",generation,a.id()))).block(WAIT);
    assertEquals(a.id(),as(A,ACTOR,store.execute(s->s.resolve(project,"TASK",generation))).block(WAIT).id());
    assertNull(as(B,ACTOR,store.execute(s->s.resolve(project,"TASK",generation))).block(WAIT));
    assertNull(as(A,ACTOR,store.execute(s->s.resolve(project,"TASK",UUID.randomUUID()))).block(WAIT));
    assertNull(as(A,ACTOR,store.execute(s->s.resolve(new UUID(0,0),"TASK",generation))).block(WAIT));
    assertNull(as(A,ACTOR,store.execute(s->s.find(DEFAULT))).block(WAIT));
    assertEquals(DomainStatus.NOT_FOUND,assertThrows(DomainException.class,()->as(A,ACTOR,store.execute(s->s.bind(project,"BUG",generation,b.id()))).block(WAIT)).getStatus());
    assertEquals(1,as(A,ACTOR,store.execute(s->s.bindings(List.of(project)).collectList())).block(WAIT).size());
    assertEquals(0,as(B,ACTOR,store.execute(s->s.bindings(List.of(project)).collectList())).block(WAIT).size());
  }

  @Test void realAppRoleRejectsForeignAndSameTenantWrongWorkflowReferences() {
    UUID project=UUID.randomUUID();var a=graph(A,project);var b=graph(B,project);var other=graph(A,UUID.randomUUID());
    var aStates=as(A,ACTOR,store.execute(s->s.statuses(a.id()).collectList())).block(WAIT);
    var bState=as(B,ACTOR,store.execute(s->s.statuses(b.id()).next())).block(WAIT);
    var otherState=as(A,ACTOR,store.execute(s->s.statuses(other.id()).next())).block(WAIT);
    for(var foreign:List.of(bState.id(),otherState.id())){
      var denied=assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.addTransition(a.id(),aStates.get(0).id(),foreign,"Wrong",false,1))).block(WAIT));
      assertEquals("23503",state(denied));
    }
    assertEquals("23503",state(assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.addStatus(b.id(),"NEW","New","TODO",40))).block(WAIT))));
    assertEquals("23503",state(assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql("INSERT INTO taska.workflow_bindings(tenant_id,project_id,issue_type,issue_type_generation,workflow_id) VALUES(:t,:p,'TASK',:g,:w)")
        .bind("t",A).bind("p",project).bind("g",UUID.randomUUID()).bind("w",b.id()).then())).block(WAIT))));
    assertNull(as(A,ACTOR,store.execute(s->s.find(b.id()))).block(WAIT));
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.statuses(b.id()).count())).block(WAIT));
  }

  @Test void failureDuringGraphCopyRollsBackInstanceAndAllGraphRows() {
    var c=copy(A);UUID project=UUID.randomUUID();
    sql("CREATE FUNCTION taska.fail_copy() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.status_key='IN_PROGRESS' THEN RAISE EXCEPTION 'fixture' USING ERRCODE='23514'; END IF; RETURN NEW; END$$");
    sql("CREATE TRIGGER fail_copy BEFORE INSERT ON taska.statuses FOR EACH ROW EXECUTE FUNCTION taska.fail_copy()");
    try{assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.instantiate(c.id(),project))).block(WAIT));}
    finally{sql("DROP TRIGGER fail_copy ON taska.statuses");sql("DROP FUNCTION taska.fail_copy()");}
    for(String table:List.of("workflow_template_instances","workflows","statuses","transitions","validator_rules"))assertEquals(0,count(table));
    var retry=as(A,ACTOR,store.execute(s->s.instantiate(c.id(),project))).block(WAIT);
    assertNotNull(retry);assertEquals(3,count("statuses"));assertEquals(6,count("transitions"));
  }

  @Test void wholeUnitOfWorkRollbackRemovesCopyGraphBindingAndReceipt() {
    UUID project=UUID.randomUUID();
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.provisionTemplate(DEFAULT).flatMap(c->s.instantiate(c.id(),project))
        .flatMap(w->s.bind(project,"TASK",UUID.randomUUID(),w.id()))
        .then(Mono.error(new IllegalStateException("fixture rollback"))))).block(WAIT));
    for(String table:TABLES)assertEquals(0,count(table));
  }

  @Test void missingContextAndEscapedSessionCannotReadTenantRowsOrWriteTenantlessWorkflow() {
    graph(A,UUID.randomUUID());graph(B,UUID.randomUUID());
    assertThrows(Exception.class,()->store.execute(s->s.copies().count()).block(WAIT));
    for(String table:TABLES)assertEquals(0L,raw.sql("SELECT count(*) n FROM taska."+table).map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    var escaped=as(A,ACTOR,store.execute(Mono::just)).block(WAIT);
    assertThrows(Exception.class,()->as(A,ACTOR,escaped.copies().count()).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql("INSERT INTO taska.workflows(name,owner_project_id) VALUES('missing',:p)").bind("p",UUID.randomUUID()).then())).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.insertWorkflow(UUID.randomUUID(),new UUID(0,0),"zero"))).block(WAIT));
  }

  @Test void templatesAndCopyReceiptsAreReadOnlyAndFutureVersionDoesNotRewriteSnapshots() {
    var c=copy(A);
    for(String statement:List.of("UPDATE taska.workflow_templates SET name='changed'","DELETE FROM taska.workflow_templates","TRUNCATE taska.workflow_templates","UPDATE taska.tenant_workflow_templates SET name='changed'"))
      assertThrows(Exception.class,()->raw.sql(statement).then().block(WAIT));
    assertThrows(Exception.class,()->operator.sql("UPDATE taska.workflow_templates SET graph='{}'").then().block(WAIT));
    UUID v2=UUID.randomUUID();publish(v2,2,1,seed);
    assertEquals(c,copy(A));assertEquals(1,c.version());
    assertNotEquals(c.id(),as(A,ACTOR,store.execute(s->s.provisionTemplate(v2))).block(WAIT).id());
  }

  @Test void deferredInstanceConstraintRejectsCommitBeforeResultIsEmitted() {
    var c=copy(A);var emitted=new AtomicBoolean();
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql("INSERT INTO taska.workflow_template_instances(tenant_id,project_id,template_copy_id,workflow_id) VALUES(:t,:p,:c,:w)")
        .bind("t",A).bind("p",UUID.randomUUID()).bind("c",c.id()).bind("w",UUID.randomUUID()).then().thenReturn("uncommitted"))
        .doOnNext(value->emitted.set(true))).block(WAIT));
    assertFalse(emitted.get());assertEquals(0,count("workflow_template_instances"));
  }

  @Test void templateRulesAreClonedAndInvalidVersionsFailWithoutPartialCopy() {
    var graph=json.readTree(seed);var edge=(ObjectNode)graph.get("transitions").get(0);
    var rule=edge.putArray("rules").addObject();rule.put("type","REQUIRE_FIELD");rule.putObject("config").put("field","resolution");
    rule.put("errorCode","REQUIRED");rule.put("errorMessage","Provide resolution");rule.put("sortOrder",10);
    UUID v2=UUID.randomUUID();publish(v2,2,1,json.writeValueAsString(graph));
    var instance=as(A,ACTOR,store.execute(s->s.provisionTemplate(v2).flatMap(c->s.instantiate(c.id(),UUID.randomUUID())))).block(WAIT);
    var rules=as(A,ACTOR,store.execute(s->s.rules(instance.id()).collectList())).block(WAIT);
    assertEquals(1,rules.size());assertEquals("REQUIRE_FIELD",rules.get(0).type());assertTrue(rules.get(0).config().contains("resolution"));
    for(int schema:List.of(1,2)) {
      UUID invalid=UUID.randomUUID();publish(invalid,10+schema,schema,schema==1?"{}":seed);
      assertEquals(DomainStatus.FAILED_PRECONDITION,assertThrows(DomainException.class,()->as(B,ACTOR,store.execute(s->s.provisionTemplate(invalid))).block(WAIT)).getStatus());
    }
    assertEquals(0L,as(B,ACTOR,store.execute(s->s.copies().count())).block(WAIT));
    assertEquals(1,count("workflows"));assertEquals(1,count("validator_rules"));
    var foreignTransition=as(A,ACTOR,store.execute(s->s.transitions(instance.id()).next())).block(WAIT);
    assertEquals("23503",state(assertThrows(Exception.class,()->as(B,ACTOR,store.execute(s->s.addRule(foreignTransition.id(),"REQUIRE_COMMENT","{}",null,null,10))).block(WAIT))));
  }

  @Test void journalsPinsAndTerminalReceiptsRetainTenantIdentityAndOriginalAuthority() {
    UUID project=UUID.randomUUID();var a=graph(A,project);graph(B,project);
    UUID pinId=UUID.randomUUID(),receiptId=UUID.randomUUID(),changeId=UUID.randomUUID();
    var receipt=new TenantWorkflowStore.Receipt(receiptId,project,"APPLIED",a.id(),"TASK",UUID.randomUUID());
    var plan=new WorkflowBindingChange.Plan(List.of("TASK"),List.of(),Map.of(),Map.of(),"Changed");
    var change=new WorkflowBindingChange(changeId,project,a.id(),ACTOR,"REPLACE_TYPES","request","node",new ActionMetadata(null,null),
        "{\"types\":[\"TASK\"]}",plan,"PENDING",null,null,Map.of(),null,false);
    var pin=as(A,ACTOR,store.execute(s->s.pin(project,pinId,a.id()).flatMap(p->s.recordReceipt(receipt).thenReturn(p))
        .flatMap(p->s.changes().insert(change).thenReturn(p)))).block(WAIT);
    assertEquals(List.of("TODO","IN_PROGRESS","DONE"),pin.keys());
    as(B,ACTOR,store.execute(s->s.releasePin(project,pinId).then(s.changes().completed(change,new byte[]{9}))
        .then(s.changes().touched(change)).then(s.receipt(project,receiptId)).doOnNext(x->fail("foreign receipt"))
        .then(s.changes().get(project,changeId)).doOnNext(x->fail("foreign journal"))
        .then(s.changes().recoverable().count()).doOnNext(n->assertEquals(0L,n)))).block(WAIT);
    assertFalse(as(A,ACTOR,store.execute(s->s.pin(project,pinId))).block(WAIT).released());
    assertEquals("PENDING",as(A,ACTOR,store.execute(s->s.changes().byRequest(project,ACTOR,"request"))).block(WAIT).state());
    var stamp=as(A,ACTOR,tx.execute(db->db.sql("SELECT actor_auth_version,membership_version,operation_fence FROM taska.workflow_binding_changes WHERE tenant_id=:t AND id=:id")
        .bind("t",A).bind("id",changeId).map((r,m)->List.of(r.get(0,Long.class),r.get(1,Long.class),r.get(2,Long.class))).one())).block(WAIT);
    assertEquals(List.of(3L,5L,1L),stamp);
    assertEquals(receipt,as(A,ACTOR,store.execute(s->s.recordReceipt(receipt))).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.recordReceipt(new TenantWorkflowStore.Receipt(receiptId,project,"CANCELLED",null,null,null)))).block(WAIT));
    as(A,ACTOR,store.execute(s->s.changes().completed(change,new byte[]{1}).then(s.changes().released(change))
        .then(s.releasePin(project,pinId)))).block(WAIT);
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.changes().recoverable().count())).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.pin(project,pinId,a.id()))).block(WAIT));
    assertEquals(DomainStatus.PERMISSION_DENIED,assertThrows(DomainException.class,()->as(A,null,store.execute(s->s.pin(project,UUID.randomUUID(),a.id()))).block(WAIT)).getStatus());
  }

  @Test void selectiveQueriesUseTenantLeadingIndexesWithoutPlannerOverrides() {
    UUID project=UUID.randomUUID();
    for(UUID tenant:List.of(A,B)) {
      sql("INSERT INTO taska.workflows(tenant_id,id,name,owner_project_id) SELECT '"+tenant+"',md5('"+tenant+"'||n::text)::uuid,'Plan','"+project+"' FROM generate_series(1,10000) n");
      sql("INSERT INTO taska.workflow_bindings(tenant_id,project_id,issue_type,issue_type_generation,workflow_id) SELECT tenant_id,owner_project_id,'TYPE_'||row_number() OVER(ORDER BY id),id,id FROM taska.workflows WHERE tenant_id='"+tenant+"'");
    }
    sql("ANALYZE taska.workflows");sql("ANALYZE taska.workflow_bindings");
    var plan=as(A,ACTOR,tx.execute(db->db.sql("EXPLAIN SELECT workflow_id FROM taska.workflow_bindings WHERE tenant_id=:t AND project_id=:p AND issue_type='TYPE_7777'")
        .bind("t",A).bind("p",project).map((r,m)->r.get(0,String.class)).all().collectList())).block(WAIT).toString();
    assertTrue(plan.contains("Index"),plan);assertTrue(plan.contains("tenant_id"),plan);assertFalse(plan.contains("Seq Scan"),plan);
  }

  @Test void existingDatabaseIsRefusedWithoutReassigningRows() throws Exception {
    sql("CREATE DATABASE legacy_guard");bootstrap("legacy_guard");
    String url=pg.getJdbcUrl().replace("/"+pg.getDatabaseName(),"/legacy_guard");
    try(var c=java.sql.DriverManager.getConnection(url,pg.getUsername(),pg.getPassword());var s=c.createStatement()) {
      s.execute("CREATE SCHEMA workflow_liquibase AUTHORIZATION taska_workflow_migrator");
      s.execute("CREATE TABLE taska.workflows(id uuid PRIMARY KEY,name text);INSERT INTO taska.workflows VALUES('11111111-1111-1111-1111-111111111111','KEEP')");
      var error=assertThrows(Exception.class,()->migrate(url));assertTrue(error.getMessage().contains("fresh service database"));
      try(var r=s.executeQuery("SELECT name FROM taska.workflows")){assertTrue(r.next());assertEquals("KEEP",r.getString(1));assertFalse(r.next());}
      try(var r=s.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='taska' AND table_name='workflows' AND column_name='tenant_id'")){r.next();assertEquals(0,r.getInt(1));}
    }
  }

  @Test void schemaAndRuntimeRolesHaveNoBypass() {
    assertThrows(Exception.class,()->new TenantStorageConfiguration().safeWorkflowRuntime(factory(pg.getUsername(),pg.getPassword())).afterPropertiesSet());
    for(String statement:List.of("SET ROLE taska_workflow_migrator","ALTER TABLE taska.workflows DISABLE ROW LEVEL SECURITY","CREATE TABLE taska.unsafe(id int)","TRUNCATE taska.workflows"))
      assertThrows(Exception.class,()->raw.sql(statement).then().block(WAIT));
    assertEquals((long)TABLES.size()+2,operator.sql("SELECT count(*) n FROM pg_class WHERE relnamespace='taska'::regnamespace AND relrowsecurity AND relforcerowsecurity")
        .map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    UUID globalId=UUID.randomUUID();
    as(A,ACTOR,store.execute(s->s.insertWorkflow(globalId,UUID.randomUUID(),"Global UUID"))).block(WAIT);
    assertEquals("23505",state(assertThrows(Exception.class,()->as(B,ACTOR,store.execute(s->s.insertWorkflow(globalId,UUID.randomUUID(),"Same UUID"))).block(WAIT))));
    assertNull(as(B,ACTOR,store.execute(s->s.find(globalId))).block(WAIT));
  }

  @Test void cancellationRemovesPreparedGraphButRetainsScopedTerminalHistory() {
    UUID project=UUID.randomUUID(),workflow=UUID.randomUUID(),operation=UUID.randomUUID();
    var change=new WorkflowBindingChange(operation,project,workflow,ACTOR,"CREATE","cancel-request","node",new ActionMetadata(null,null),"{}",
        new WorkflowBindingChange.Plan(List.of("TASK"),List.of(),Map.of(),Map.of(),"Prepared"),"PENDING",null,null,Map.of(),null,false);
    as(A,ACTOR,store.execute(s->s.insertWorkflow(workflow,project,"Prepared",false).then(s.changes().insert(change)))).block(WAIT);
    var foreign=graph(B,project);
    var wrong=new WorkflowBindingChange(UUID.randomUUID(),project,foreign.id(),ACTOR,"CREATE","foreign","node",new ActionMetadata(null,null),"{}",change.plan(),"PENDING",null,null,Map.of(),null,false);
    assertEquals(DomainStatus.NOT_FOUND,assertThrows(DomainException.class,()->as(A,ACTOR,store.execute(s->s.changes().insert(wrong))).block(WAIT)).getStatus());
    assertEquals(0L,as(B,ACTOR,store.execute(s->s.discardPrepared(project,workflow))).block(WAIT));
    as(A,ACTOR,store.execute(s->s.changes().cancelling(change,new DomainException(DomainStatus.ABORTED,"Cancelled"))
        .then(s.discardPrepared(project,workflow)).doOnNext(n->assertEquals(1L,n))
        .then(s.changes().cancelled(change)).then(s.changes().released(change)))).block(WAIT);
    assertNull(as(A,ACTOR,store.execute(s->s.find(workflow))).block(WAIT));
    assertEquals("CANCELLED",as(A,ACTOR,store.execute(s->s.changes().get(project,operation))).block(WAIT).state());
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.changes().recoverable().count())).block(WAIT));
    assertNull(as(B,ACTOR,store.execute(s->s.changes().get(project,operation))).block(WAIT));
    UUID historyGraph=UUID.randomUUID(),pin=UUID.randomUUID();
    var receipt=new TenantWorkflowStore.Receipt(UUID.randomUUID(),project,"APPLIED",historyGraph,"STORY",UUID.randomUUID());
    as(A,ACTOR,store.execute(s->s.insertWorkflow(historyGraph,project,"Historical")
        .then(s.addStatus(historyGraph,"TODO","To Do","TODO",10)).then(s.pin(project,pin,historyGraph))
        .then(s.releasePin(project,pin)).then(s.recordReceipt(receipt)))).block(WAIT);
    as(A,ACTOR,tx.execute(db->db.sql("DELETE FROM taska.workflows WHERE tenant_id=:t AND id=:id")
        .bind("t",A).bind("id",historyGraph).then())).block(WAIT);
    assertEquals(receipt,as(A,null,store.execute(s->s.recordReceipt(receipt))).block(WAIT));
    assertTrue(as(A,ACTOR,store.execute(s->s.pin(project,pin))).block(WAIT).released());
  }

  @Test void unavailableStartupRoleProbeHasAnExplicitFailureAndCause() {
    var unavailable=new ConnectionFactory(){
      public Mono<? extends Connection> create(){return Mono.error(new IllegalStateException("offline fixture"));}
      public ConnectionFactoryMetadata getMetadata(){return ()->"PostgreSQL";}
    };
    var error=assertThrows(IllegalStateException.class,()->new TenantStorageConfiguration().safeWorkflowRuntime(unavailable).afterPropertiesSet());
    assertTrue(error.getMessage().contains("role verification failed during startup"));assertNotNull(error.getCause());
  }

  @Test void tenantProfileStartsHealthyWithoutLegacyRepositoriesOrResolvers() throws Exception {
    try(var app=new SpringApplicationBuilder(WorkflowServiceApplication.class).web(WebApplicationType.REACTIVE).profiles("tenant-storage").run(
        "--server.port=0","--server.address=127.0.0.1","--spring.r2dbc.url=r2dbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+pg.getDatabaseName(),
        "--spring.r2dbc.username=taska_workflow_app","--spring.r2dbc.password="+APP,"--spring.liquibase.url="+pg.getJdbcUrl(),
        "--spring.liquibase.user=taska_workflow_deployer","--spring.liquibase.password="+DEPLOY,"--spring.grpc.server.port=0","--spring.grpc.server.host=127.0.0.1")) {
      assertNotNull(app.getBean(TenantWorkflowStore.class));
      assertTrue(app.getBeansOfType(ru.taska.repository.WorkflowRepository.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.service.WorkflowResolver.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.service.WorkflowBindingCoordinator.class).isEmpty());
      int port=Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
      var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/health")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200,response.statusCode());assertTrue(response.body().contains("UP"));
    }
  }
}
