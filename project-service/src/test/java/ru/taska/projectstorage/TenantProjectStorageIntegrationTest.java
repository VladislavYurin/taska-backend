package ru.taska.projectstorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static ru.taska.security.tenant.ProjectTestContext.as;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import ru.taska.ProjectServiceApplication;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeCreation;
import ru.taska.domain.IssueTypeDeletion;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.projectbootstrap.TenantStorageConfiguration;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Timeout(90)
class TenantProjectStorageIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final UUID A=UUID.randomUUID(), B=UUID.randomUUID(), ACTOR=UUID.randomUUID();
  static final Duration WAIT=Duration.ofSeconds(20);
  static final String APP=UUID.randomUUID().toString(), DEPLOY=UUID.randomUUID().toString();
  static final List<String> TABLES=List.of("projects","project_members","project_settings","project_issue_types",
      "project_type_operations","issue_type_creations","issue_type_deletions","outbox_events","project_creation_operations","project_authorization_audit","project_lifecycle_commands",
          "project_deletion_operations",
          "project_resource_identities");
  static DatabaseClient operator, raw;
  static ConnectionPool pool;
  static TenantTransactionExecutor tx;
  static TenantProjectStore store;

  static ConnectionFactory factory(String user,String password) {
    return ConnectionFactories.get(ConnectionFactoryOptions.builder()
        .option(ConnectionFactoryOptions.DRIVER,"postgresql").option(ConnectionFactoryOptions.HOST,pg.getHost())
        .option(ConnectionFactoryOptions.PORT,pg.getMappedPort(5432)).option(ConnectionFactoryOptions.DATABASE,pg.getDatabaseName())
        .option(ConnectionFactoryOptions.USER,user).option(ConnectionFactoryOptions.PASSWORD,password).build());
  }

  @BeforeAll static void setup() throws Exception {
    for (String ext:List.of("sql","psql")) pg.copyFileToContainer(
        MountableFile.forClasspathResource("db/tenant/bootstrap."+ext),"/tmp/bootstrap."+ext);
    var result=pg.execInContainer("psql","-X","-v","ON_ERROR_STOP=1","-U",pg.getUsername(),"-d",pg.getDatabaseName(),
        "-v","service=project","-v","schema=taska","-f","/tmp/bootstrap.psql");
    assertEquals(0,result.getExitCode(),result.getStderr());
    operator=DatabaseClient.create(factory(pg.getUsername(),pg.getPassword()));
    sql("CREATE EXTENSION pgcrypto");
    sql("CREATE SCHEMA project_liquibase AUTHORIZATION taska_project_migrator");
    sql("REVOKE ALL ON SCHEMA project_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_project_app PASSWORD '"+APP+"'");
    sql("ALTER ROLE taska_project_deployer PASSWORD '"+DEPLOY+"'");
    migrate(); migrate();
    pool=new ConnectionPool(ConnectionPoolConfiguration.builder(factory("taska_project_app",APP))
        .initialSize(1).maxSize(2).maxAcquireTime(WAIT).build());
    raw=DatabaseClient.create(pool); tx=new TenantTransactionExecutor(pool);
    store=new TenantProjectStore(tx,new ObjectMapper());
    new TenantStorageConfiguration().safeProjectRuntime(pool).afterPropertiesSet();
  }

  static void migrate() throws Exception {
    var migration=new TenantStorageConfiguration().projectTenantLiquibase(pg.getJdbcUrl(),"taska_project_deployer",DEPLOY);
    migration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
    migration.afterPropertiesSet();
  }
  static void sql(String text) { operator.sql(text).fetch().rowsUpdated().block(WAIT); }
  @AfterAll static void close() { if(pool!=null)pool.disposeLater().block(WAIT); }
  @BeforeEach void reset() { sql("TRUNCATE taska.resource_operation_receipts"); sql("TRUNCATE "+TABLES.stream().map(t->"taska."+t).collect(java.util.stream.Collectors.joining(","))+" CASCADE"); }

  Project create(UUID tenant,String key) {
    return as(tenant,ACTOR,store.execute(s -> s.insertProject(UUID.randomUUID(),key,"Test",ACTOR)
        .flatMap(p -> s.addMember(p.getId(),ACTOR,ProjectRole.ADMIN,ACTOR)
            .then(s.settings(p.getId(),"{\"retained\":true}",ACTOR))
            .then(s.types().seedDefaults(p.getId())).thenReturn(p)))).block(WAIT);
  }

  @Test void sameKeyIsIndependentAndConcurrentDuplicateRollsBack() {
    var a=create(A,"SAME"); var b=create(B,"SAME");
    assertNotEquals(a.getId(),b.getId());
    assertThrows(Exception.class,()->create(A,"SAME"));
    var attempts=Flux.merge(
        as(A,ACTOR,store.execute(s->s.insertProject(UUID.randomUUID(),"RACE","one",ACTOR))).materialize(),
        as(A,ACTOR,store.execute(s->s.insertProject(UUID.randomUUID(),"RACE","two",ACTOR))).materialize())
        .filter(signal->!signal.isOnComplete()).collectList().block(WAIT);
    assertEquals(1,attempts.stream().filter(Signal::isOnNext).count());
    assertEquals(1,attempts.stream().filter(Signal::isOnError).count());
    assertEquals(2L,as(A,ACTOR,store.execute(s->s.countProjects())).block(WAIT));
    assertEquals(1L,as(B,ACTOR,store.execute(s->s.countProjects())).block(WAIT));
  }

  @Test void crossTenantReadsBatchSettingsMembersAndWritesAreIsolated() {
    var a=create(A,"A");var b=create(B,"B");
    as(A,ACTOR,store.execute(s->s.find(b.getId()).doOnNext(p->fail("foreign project"))
        .then(s.infoBatch(List.of(a.getId(),b.getId())).collectList())
        .doOnNext(rows->assertEquals(List.of(a.getId()),rows.stream().map(x->x.id()).toList()))
        .then(s.settings(b.getId())).doOnNext(x->fail("foreign settings"))
        .then(s.members(b.getId()).count()).doOnNext(n->assertEquals(0L,n))
        .then(s.changeRole(b.getId(),ACTOR,ProjectRole.VIEWER)).doOnNext(n->assertEquals(0L,n))
        .then(s.removeMember(b.getId(),ACTOR)).doOnNext(n->assertEquals(0L,n)))).block(WAIT);
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.update(b.toBuilder().name("stolen").build()))).block(WAIT));
    assertEquals("Test",as(B,ACTOR,store.execute(s->s.find(b.getId()))).block(WAIT).getName());
    assertEquals(ProjectRole.ADMIN,as(B,ACTOR,store.execute(s->s.members(b.getId()).single())).block(WAIT).getRole());
    assertTrue(as(B,ACTOR,store.execute(s->s.settings(b.getId()))).block(WAIT).contains("true"));
  }

  @Test void realAppRoleRejectsForeignFkTenantMutationAndMissingTenant() {
    var a=create(A,"A"); var b=create(B,"B");
    for(var project:List.of(b.getId(),UUID.randomUUID())) {
      assertEquals(DomainStatus.NOT_FOUND,assertThrows(DomainException.class,()->as(A,ACTOR,store.execute(s->s.settings(project,"{}",ACTOR))).block(WAIT)).getStatus());
      assertEquals(DomainStatus.NOT_FOUND,assertThrows(DomainException.class,()->as(A,ACTOR,store.execute(s->s.addMember(project,UUID.randomUUID(),ProjectRole.MEMBER,ACTOR))).block(WAIT)).getStatus());
      // Also bypass repository prechecks to prove real PostgreSQL FK rejection as the app LOGIN.
      for(String statement:List.of(
              "INSERT INTO taska.project_settings(tenant_id,project_id,updated_by)"
                  + " VALUES(:t,:p,:actor)",
              "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by)"
                  + " VALUES(:t,:p,:actor,'MEMBER',:actor)")) {
        var rejected=assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql(statement)
            .bind("t",A).bind("p",project).bind("actor",ACTOR).then())).block(WAIT));
        assertEquals("23503",sqlState(rejected));
      }
    }
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql("UPDATE taska.projects SET tenant_id=:b WHERE id=:id")
        .bind("b",B).bind("id",a.getId()).then())).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql(
                                    "INSERT INTO taska.projects(project_key,name,created_by)"
                                        + " VALUES('MISSING','x',:actor)")
        .bind("actor",ACTOR).then())).block(WAIT));
    assertEquals(0L,raw.sql("SELECT count(*) n FROM taska.projects").map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    assertThrows(Exception.class,()->store.execute(s->s.countProjects()).block(WAIT));
    assertThrows(Exception.class,()->raw.sql(
                    "INSERT INTO taska.projects(tenant_id,project_key,name,created_by)"
                        + " VALUES(:t,'NO_CONTEXT','x',:a)")
        .bind("t",A).bind("a",ACTOR).then().block(WAIT));
    assertEquals(a.getId(),as(A,ACTOR,store.execute(s->s.findByKey("A"))).block(WAIT).getId());
  }

  @Test void archiveKeepsUuidQuotaAndOtherTenantAndEmitsOneEvent() {
    var a=create(A,"SAME");var b=create(B,"SAME");
    var results=Flux.merge(as(A,ACTOR,store.execute(s->s.archive(a.getId()))),as(A,ACTOR,store.execute(s->s.archive(a.getId()))))
        .collectList().block(WAIT);
    assertEquals(results.get(0).getArchivedAt(),results.get(1).getArchivedAt());
    assertEquals(a.getId(),results.get(0).getId());
    assertEquals(1L,as(A,ACTOR,store.execute(s->s.countProjects())).block(WAIT));
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.listForUser(ACTOR).count())).block(WAIT));
    assertEquals(1L,as(A,ACTOR,store.execute(s->s.eventIds(a.getId()).count())).block(WAIT));
    assertNull(as(B,ACTOR,store.execute(s->s.find(b.getId()))).block(WAIT).getArchivedAt());
    assertEquals(1L,as(B,ACTOR,store.execute(s->s.listForUser(ACTOR).count())).block(WAIT));
    assertThrows(Exception.class,()->as(B,ACTOR,store.execute(s->s.archive(a.getId()))).block(WAIT));
  }

  @Test void memberCannotArchiveAndFailureRollsBackArchiveOutbox() {
    var p=create(A,"A");var viewer=UUID.randomUUID();
    as(A,ACTOR,store.execute(s->s.addMember(p.getId(),viewer,ProjectRole.VIEWER,ACTOR))).block(WAIT);
    assertThrows(Exception.class,()->as(A,viewer,store.execute(s->s.archive(p.getId()))).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.archive(p.getId())
        .then(Mono.error(new IllegalStateException("injected rollback"))))).block(WAIT));
    assertNull(as(A,ACTOR,store.execute(s->s.find(p.getId()))).block(WAIT).getArchivedAt());
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.eventIds(p.getId()).count())).block(WAIT));
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql("DELETE FROM taska.projects WHERE tenant_id=:t AND id=:id")
        .bind("t",A).bind("id",p.getId()).then())).block(WAIT));
  }

  @Test void unitOfWorkRollsBackAllRowsAndPoolDoesNotLeakTenant() {
    UUID id=UUID.randomUUID();
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.insertProject(id,"ROLLBACK","x",ACTOR)
        .then(s.addMember(id,ACTOR,ProjectRole.ADMIN,ACTOR)).then(s.types().seedDefaults(id))
        .then(s.appendEvent(id,"test","{}"))
        .then(s.settings(UUID.randomUUID(),"{}",ACTOR)))).block(WAIT));
    for(UUID tenant:List.of(A,B)) assertEquals(0L,as(tenant,ACTOR,store.execute(s->s.countProjects())).block(WAIT));
    for(String table:TABLES) assertEquals(0L,operator.sql("SELECT count(*) n FROM taska."+table).map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    var escaped=as(A,ACTOR,store.execute(Mono::just)).block(WAIT);
    assertThrows(Exception.class,()->as(A,ACTOR,escaped.countProjects()).block(WAIT));
  }

  @Test void registryBatchReorderAndSettingsUseSameScope() {
    var a=create(A,"A");var b=create(B,"B");
    as(A,ACTOR,store.execute(s->s.types().reorder(a.getId(),List.of("BUG","STORY","TASK"))
        .then(s.types().syncLegacySettings(a.getId(),ACTOR))
        .then(s.types().listBatch(List.of(a.getId(),b.getId())).collectList())
        .doOnNext(rows->{assertEquals(3,rows.size());assertEquals("BUG",rows.get(0).code());})
        .then(s.types().list(b.getId()).count()).doOnNext(n->assertEquals(0L,n)))).block(WAIT);
    assertEquals("TASK",as(B,ACTOR,store.execute(s->s.types().list(b.getId()).next())).block(WAIT).code());
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.types().seedDefaults(b.getId()))).block(WAIT));
  }

  @Test void rolesAndPoliciesHaveNoPrivilegeBypass() {
    assertThrows(Exception.class,()->new TenantStorageConfiguration().safeProjectRuntime(factory(pg.getUsername(),pg.getPassword())).afterPropertiesSet());
    for(String statement:List.of("SET ROLE taska_project_migrator","TRUNCATE taska.projects","ALTER TABLE taska.projects DISABLE ROW LEVEL SECURITY",
        "CREATE TABLE taska.unsafe(id int)","UPDATE taska.outbox_events SET status='PUBLISHED'"))
      assertThrows(Exception.class,()->raw.sql(statement).then().block(WAIT));
    assertEquals((long) TABLES.size()+3,operator.sql(
                "SELECT count(*) n FROM pg_class WHERE relnamespace='taska'::regnamespace AND"
                    + " relrowsecurity AND relforcerowsecurity")
        .map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    for(String table:TABLES) assertEquals(0L,raw.sql("SELECT count(*) n FROM taska."+table).map((r,m)->r.get("n",Long.class)).one().block(WAIT));
  }

  @Test void journalCommandsDenyMissingOrDifferentHumanActorWithoutPartialOperation() {
    var a=create(A,"A");UUID id=UUID.randomUUID();
    assertEquals(DomainStatus.PERMISSION_DENIED,assertThrows(DomainException.class,()->as(A,null,store.execute(s->
        s.operations().reserve(a.getId(),id,"CREATE_TYPE"))).block(WAIT)).getStatus());
    var type=new ProjectIssueType(a.getId(),"CUSTOM","Custom","blue",null,"square",40,null,null,UUID.randomUUID());
    var intent=new IssueTypeCreation(id,a.getId(),UUID.randomUUID(),"request","node","digest",type,UUID.randomUUID(),
        null,null,null,"PENDING",new ActionMetadata(null,null),null,null,Map.of());
    assertEquals(DomainStatus.PERMISSION_DENIED,assertThrows(DomainException.class,()->as(A,ACTOR,store.execute(s->
        s.operations().reserve(a.getId(),id,"CREATE_TYPE").then(Mono.defer(()->s.creations().insert(intent))))).block(WAIT)).getStatus());
    assertNull(as(A,ACTOR,store.execute(s->s.operations().find(a.getId(),id))).block(WAIT));
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.creations().recoverable().count())).block(WAIT));
  }

  static String sqlState(Throwable error) {
    for(Throwable cause=error;cause!=null;cause=cause.getCause()) if(cause instanceof R2dbcException sql) return sql.getSqlState();
    return null;
  }

  @Test void journalsAndFencesAreTenantAndProjectBound() {
    var a=create(A,"A");var b=create(B,"B");
    UUID operation=UUID.randomUUID();
    var source=as(A,ACTOR,store.execute(s->s.types().find(a.getId(),"TASK"))).block(WAIT);
    var intent=new IssueTypeDeletion(operation,a.getId(),"TASK",source.generation(),null,null,ACTOR,Map.of(),"PENDING",
        "request","node",new ActionMetadata(null,null),null,null,Map.of(),false);
    as(A,ACTOR,store.execute(s->s.lock(a.getId()).then(s.operations().reserve(a.getId(),operation,"DELETE_TYPE"))
        .then(s.deletions().insert(intent)).then(s.deletions().fence(intent))
        .doOnNext(n->assertEquals(1L,n)))).block(WAIT);
    assertEquals(1L,as(A,ACTOR,store.execute(s->s.deletions().recoverable().count())).block(WAIT));
    var authority=as(A,ACTOR,store.execute(s->s.operationAuthority(a.getId(),operation))).block(WAIT);
    assertEquals(3L,authority.actorVersion());assertEquals(5L,authority.membershipVersion());assertEquals(1L,authority.fence());
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql(
                                    "UPDATE taska.project_type_operations SET actor_auth_version=99"
                                        + " WHERE tenant_id=:t AND id=:id")
        .bind("t",A).bind("id",operation).then())).block(WAIT));
    as(B,ACTOR,store.execute(s->s.deletions().find(a.getId(),operation).doOnNext(x->fail("foreign journal"))
        .then(s.deletions().recoverable().count()).doOnNext(n->assertEquals(0L,n))
        .then(s.deletions().clearFence(intent)).then(s.deletions().terminal(intent,"CANCELLED"))
        .then(s.deletions().touched(intent)))).block(WAIT);
    assertEquals("PENDING",as(A,ACTOR,store.execute(s->s.deletions().pending(a.getId()))).block(WAIT).state());
    assertEquals(operation,as(A,ACTOR,store.execute(s->s.types().find(a.getId(),"TASK"))).block(WAIT).pendingOperationId());
    // Even within one tenant a fence cannot reference an operation owned by another project.
    var other=create(A,"OTHER");
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql(
                                    "UPDATE taska.project_issue_types SET pending_operation_id=:o"
                                        + " WHERE tenant_id=:t AND project_id=:p")
        .bind("o",operation).bind("t",A).bind("p",other.getId()).then())).block(WAIT));
    as(A,ACTOR,store.execute(s->s.deletions().deleteSource(intent).doOnNext(n->assertEquals(1L,n))
        .then(s.deletions().clearFence(intent)).then(s.deletions().terminal(intent,"COMPLETED"))
        .then(s.deletions().released(intent)).then(s.operations().close(a.getId(),operation,"DELETE_TYPE"))
        .then(s.deletions().completedRequest(a.getId(),ACTOR,"TASK","request"))
        .doOnNext(x->assertEquals(operation,x.id())))).block(WAIT);
    assertEquals(0L,as(A,ACTOR,store.execute(s->s.deletions().recoverable().count())).block(WAIT));
    assertEquals(3L,as(B,ACTOR,store.execute(s->s.types().list(b.getId()).count())).block(WAIT));
  }

  @Test void creationJournalRecoveryAndOperationIdentityCannotCrossTenant() {
    var a=create(A,"A");create(B,"B");
    UUID operation=UUID.randomUUID(), workflow=UUID.randomUUID();
    var type=new ProjectIssueType(a.getId(),"CUSTOM","Custom","blue",null,"square",40,null,null,UUID.randomUUID());
    var intent=new IssueTypeCreation(operation,a.getId(),ACTOR,"req","node","digest",type,workflow,
        null,null,null,"PENDING",new ActionMetadata(null,null),null,null,Map.of());
    as(A,ACTOR,store.execute(s->s.lock(a.getId()).then(s.operations().reserve(a.getId(),operation,"CREATE_TYPE"))
        .then(s.creations().insert(intent)).then(s.creations().request(a.getId(),ACTOR,"req"))
        .doOnNext(x->assertEquals(type.generation(),x.type().generation()))
        .then(s.creations().recoverable().count()).doOnNext(n->assertEquals(1L,n)))).block(WAIT);
    as(B,ACTOR,store.execute(s->s.creations().completed(intent,workflow)
        .then(s.creations().cancelled(intent)).then(s.creations().touched(intent))
        .then(s.creations().find(a.getId(),operation)).doOnNext(x->fail("foreign creation"))
        .then(s.operations().active(a.getId())).doOnNext(x->fail("foreign operation")))).block(WAIT);
    assertThrows(Exception.class,()->as(B,ACTOR,store.execute(s->s.operations().close(a.getId(),operation,"CREATE_TYPE"))).block(WAIT));
    assertEquals("PENDING",as(A,ACTOR,store.execute(s->s.creations().find(a.getId(),operation))).block(WAIT).state());
    as(A,ACTOR,store.execute(s->s.creations().completed(intent,workflow)
        .then(s.operations().close(a.getId(),operation,"CREATE_TYPE"))
        .then(s.creations().recoverable().count()).doOnNext(n->assertEquals(0L,n)))).block(WAIT);
    assertThrows(Exception.class,()->as(A,ACTOR,store.execute(s->s.operations().reserve(a.getId(),operation,"CREATE_TYPE"))).block(WAIT));
  }

  @Test void selectiveQueryPlansUseTenantLeadingIndexesWithoutPlannerOverrides() {
    for(UUID tenant:List.of(A,B)) {
      sql("INSERT INTO taska.projects(tenant_id,id,project_key,name,created_by) SELECT '"+tenant+"',md5('"+tenant+"'||n::text)::uuid,'P'||n,'plan','"+ACTOR+"' FROM generate_series(1,20000) n");
      sql(
          "INSERT INTO taska.project_members(tenant_id,project_id,user_id,role,added_by) SELECT"
              + " tenant_id,id,md5(project_key)::uuid,'ADMIN',created_by FROM taska.projects WHERE"
              + " tenant_id='"+tenant+"'");
    }
    sql("ANALYZE taska.projects");sql("ANALYZE taska.project_members");
    var keyPlan=as(A,ACTOR,tx.execute(db->db.sql(
                                "EXPLAIN SELECT id FROM taska.projects WHERE tenant_id=:t AND"
                                    + " project_key='P19321'")
        .bind("t",A).map((r,m)->r.get(0,String.class)).all().collectList())).block(WAIT).toString();
    assertTrue(keyPlan.contains("tenant_id"),keyPlan);assertFalse(keyPlan.contains("Seq Scan"),keyPlan);
    assertTrue(keyPlan.contains("projects_tenant_id_project_key_key"),keyPlan);
    var memberPlan=as(A,ACTOR,tx.execute(db->db.sql(
                                "EXPLAIN SELECT project_id FROM taska.project_members WHERE"
                                    + " tenant_id=:t AND user_id=md5('P19321')::uuid")
        .bind("t",A).map((r,m)->r.get(0,String.class)).all().collectList())).block(WAIT).toString();
    assertTrue(memberPlan.contains("project_members_user_idx"),memberPlan);
    assertFalse(memberPlan.contains("Seq Scan"),memberPlan);
  }

  @Test void tenantProfileStartsWithoutLegacyRepositoriesRpcOrSchedulers() {
    try(var app=new SpringApplicationBuilder(ProjectServiceApplication.class).web(WebApplicationType.NONE).profiles("tenant-storage").run(
        "--spring.r2dbc.url=r2dbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/"+pg.getDatabaseName(),
        "--spring.r2dbc.username=taska_project_app","--spring.r2dbc.password="+APP,
        "--spring.liquibase.url="+pg.getJdbcUrl(),"--spring.liquibase.user=taska_project_deployer","--spring.liquibase.password="+DEPLOY,
        "--spring.grpc.server.port=0","--spring.grpc.server.host=127.0.0.1")) {
      assertNotNull(app.getBean(TenantProjectStore.class));
      assertTrue(app.getBeansOfType(ru.taska.repository.ProjectRepository.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.transport.kafka.OutboxEventScheduler.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.transport.grpc.GrpcProjectService.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.service.IssueTypeCreationCoordinator.class).isEmpty());
    }
  }

  @Test void existingDatabaseIsRejectedWithoutChangingItsBusinessRows() throws Exception {
    // Separate database in this test's container; never target the configured/shared project DB.
    sql("CREATE DATABASE legacy_guard");
    var result=pg.execInContainer("psql","-X","-v","ON_ERROR_STOP=1","-U",pg.getUsername(),"-d","legacy_guard",
        "-v","service=project","-v","schema=taska","-f","/tmp/bootstrap.psql");
    assertEquals(0,result.getExitCode(),result.getStderr());
    String url=pg.getJdbcUrl().replace("/"+pg.getDatabaseName(),"/legacy_guard");
    try(var connection=java.sql.DriverManager.getConnection(url,pg.getUsername(),pg.getPassword());var statement=connection.createStatement()) {
      statement.execute("CREATE SCHEMA project_liquibase AUTHORIZATION taska_project_migrator");
      statement.execute(
          "CREATE TABLE taska.projects(id uuid PRIMARY KEY,project_key text);INSERT INTO"
              + " taska.projects VALUES('00000000-0000-0000-0000-000000000099','KEEP')");
      var migration=new TenantStorageConfiguration().projectTenantLiquibase(url,"taska_project_deployer",DEPLOY);
      migration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
      assertThrows(Exception.class,migration::afterPropertiesSet);
      try(var rows=statement.executeQuery("SELECT project_key FROM taska.projects")) {assertTrue(rows.next());assertEquals("KEEP",rows.getString(1));assertFalse(rows.next());}
      try(var rows=statement.executeQuery(
              "SELECT count(*) FROM information_schema.columns WHERE table_schema='taska' AND"
                  + " table_name='projects' AND column_name='tenant_id'")) {
        rows.next();assertEquals(0L,rows.getLong(1));
      }
    }
  }

  // SQL/transaction regression: primary authority is controlled here; real authority is covered by
  // mTLS E2E.
  TenantProjectApi lifecycleApi(boolean implicit, boolean audited) {
    var facts=org.mockito.Mockito.mock(ru.taska.capability.TenantDecisionFacts.class);
    org.mockito.Mockito.when(facts.current(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> Mono.deferContextual(c -> {
      var f=org.mockito.Mockito.mock(ru.taska.capability.TenantDecisionFacts.Frame.class);
      org.mockito.Mockito.when(f.context()).thenReturn(ru.taska.security.tenant.VerifiedTenantContext.require(c));
      org.mockito.Mockito.when(f.implicitAdmin()).thenReturn(implicit);
      org.mockito.Mockito.when(f.membership()).thenReturn(ru.taska.api.b2b.v1.MembershipSnapshot.newBuilder().setVersion("1").build());
      return Mono.just(f);
    }));
    org.mockito.Mockito.when(facts.currentAdministrative(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(i -> facts.current("write"));
    org.mockito.Mockito.when(facts.decision(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(ru.taska.capability.CapabilityFacts.Target.class),
        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      var role=invocation.getArgument(3,ru.taska.capability.CapabilityFacts.ProjectRole.class);
      if(role!=ru.taska.capability.CapabilityFacts.ProjectRole.ADMIN)
        return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"SCOPE_DENIED"));
      var decision=org.mockito.Mockito.mock(ru.taska.capability.CapabilityEvaluator.Decision.class);
      org.mockito.Mockito.when(decision.auditRequired()).thenReturn(audited);
      org.mockito.Mockito.when(decision.bypassedRestrictions()).thenReturn(Set.of());
      return Mono.just(decision);
    });
    var mapper=new ru.taska.mapper.ProjectMapperImpl();
    org.springframework.test.util.ReflectionTestUtils.setField(mapper,"projectMemberMapper",new ru.taska.mapper.ProjectMemberMapperImpl());
    return new TenantProjectApi(store,facts,org.mockito.Mockito.mock(ru.taska.capability.ManagedDomainReads.class),
        mapper,null,new ObjectMapper());
  }

  ru.taska.api.project.v1.ProjectResponse transition(TenantProjectApi api,UUID tenant,UUID actor,UUID id,
      boolean archive,Integer version,String key) {
    return as(tenant,actor,api.lifecycle(id.toString(),actor.toString(),archive,version,key)).block(WAIT);
  }

  @Test void lifecycleEffectiveAdminReplayPreservesDataQuotaAndAudits() {
    var p=create(A,"LIFE");
    sql("DELETE FROM taska.project_members WHERE project_id='"+p.getId()+"'");
    var api=lifecycleApi(true,true);var key=UUID.randomUUID().toString();
    var archived=transition(api,A,ACTOR,p.getId(),true,p.getVersion(),key);
    assertTrue(archived.hasArchivedAt());assertEquals(p.getVersion()+1,archived.getVersion());
    var restored=transition(api,A,ACTOR,p.getId(),false,archived.getVersion(),UUID.randomUUID().toString());
    assertFalse(restored.hasArchivedAt());assertEquals(archived.getVersion()+1,restored.getVersion());
    assertEquals(archived,transition(api,A,ACTOR,p.getId(),true,p.getVersion(),key));
    assertEquals(restored,transition(api,A,ACTOR,p.getId(),false,restored.getVersion(),UUID.randomUUID().toString()));
    assertEquals(1L,as(A,ACTOR,store.execute(TenantProjectStore.Session::countProjects)).block(WAIT));
    assertEquals(2L,as(A,ACTOR,store.execute(q->q.eventIds(p.getId()).count())).block(WAIT));
    assertTrue(as(A,ACTOR,store.execute(q->q.settings(p.getId()))).block(WAIT).contains("retained"));
    assertEquals(3L,as(A,ACTOR,store.execute(q->q.types().list(p.getId()).count())).block(WAIT));
    assertEquals(4L,operator.sql("SELECT count(*) n FROM taska.project_authorization_audit").map((r,m)->r.get("n",Long.class)).one().block(WAIT));
    var conflict=assertThrows(DomainException.class,()->transition(api,A,ACTOR,p.getId(),false,p.getVersion(),key));
    assertEquals("IDEMPOTENCY_CONFLICT",conflict.getDetails().get("code"));
  }

  @Test void lifecycleDeniedForeignStaleAndMissingVersionHaveNoEffects() {
    var p=create(A,"LIFE");var foreign=create(B,"OTHER");var api=lifecycleApi(false,false);
    sql("UPDATE taska.project_members SET root_membership_version=1 WHERE project_id='"+p.getId()+"'");
    assertEquals(DomainStatus.NOT_FOUND,assertThrows(DomainException.class,()->
        transition(api,A,ACTOR,foreign.getId(),true,0,UUID.randomUUID().toString())).getStatus());
    assertEquals(DomainStatus.PERMISSION_DENIED,assertThrows(DomainException.class,()->
        transition(api,A,UUID.randomUUID(),p.getId(),true,0,UUID.randomUUID().toString())).getStatus());
    assertEquals(DomainStatus.FAILED_PRECONDITION,assertThrows(DomainException.class,()->
        transition(api,A,ACTOR,p.getId(),true,null,UUID.randomUUID().toString())).getStatus());
    assertEquals(DomainStatus.ABORTED,assertThrows(DomainException.class,()->
        transition(api,A,ACTOR,p.getId(),true,9,UUID.randomUUID().toString())).getStatus());
    assertNull(as(A,ACTOR,store.execute(q->q.find(p.getId()))).block(WAIT).getArchivedAt());
    assertEquals(0L,as(A,ACTOR,store.execute(q->q.eventIds(p.getId()).count())).block(WAIT));
    // A grant from an earlier root membership incarnation is insufficient.
    sql("UPDATE taska.project_members SET root_membership_version=2 WHERE project_id='"+p.getId()+"'");
    assertEquals(DomainStatus.PERMISSION_DENIED,assertThrows(DomainException.class,()->
        transition(api,A,ACTOR,p.getId(),true,0,UUID.randomUUID().toString())).getStatus());
  }

  @Test void lifecycleParallelCommandsDoNotOverwriteAndReceiptFailureRollsBack() {
    var p=create(A,"LIFE");var api=lifecycleApi(true,false);
    var results=Flux.merge(as(A,ACTOR,api.lifecycle(p.getId().toString(),ACTOR.toString(),true,0,UUID.randomUUID().toString())).materialize(),
        as(A,ACTOR,api.lifecycle(p.getId().toString(),ACTOR.toString(),true,0,UUID.randomUUID().toString())).materialize())
        .filter(signal->!signal.isOnComplete()).collectList().block(WAIT);
    assertEquals(1,results.stream().filter(Signal::isOnNext).count());
    assertEquals(1,results.stream().filter(Signal::isOnError).count());
    var archived=as(A,ACTOR,store.execute(q->q.find(p.getId()))).block(WAIT);
    var rejected=UUID.randomUUID().toString();
    sql(
        "ALTER TABLE taska.project_lifecycle_commands ADD CONSTRAINT injected_receipt_failure"
            + " CHECK(command_key<>'"+rejected+"'::uuid)");
    try { assertThrows(Exception.class,()->transition(api,A,ACTOR,p.getId(),false,archived.getVersion(),rejected)); }
    finally { sql("ALTER TABLE taska.project_lifecycle_commands DROP CONSTRAINT injected_receipt_failure"); }
    var after=as(A,ACTOR,store.execute(q->q.find(p.getId()))).block(WAIT);
    assertEquals(archived.getVersion(),after.getVersion());assertEquals(archived.getArchivedAt(),after.getArchivedAt());
    assertEquals(1L,as(A,ACTOR,store.execute(q->q.eventIds(p.getId()).count())).block(WAIT));
    assertFalse(transition(api,A,ACTOR,p.getId(),false,archived.getVersion(),rejected).hasArchivedAt());
    assertThrows(Exception.class,()->as(A,ACTOR,tx.execute(db->db.sql(
                                    "UPDATE taska.project_lifecycle_commands SET"
                                        + " action='project.restore'").then())).block(WAIT));
    assertEquals(0L,raw.sql("SELECT count(*) n FROM taska.project_lifecycle_commands").map((r,m)->r.get("n",Long.class)).one().block(WAIT));
  }

  ru.taska.api.b2b.v1.DeleteProjectRpcRequest deleteRequest(
      Project p, UUID key, int version, String confirmed) {
    return ru.taska.api.b2b.v1.DeleteProjectRpcRequest.newBuilder()
        .setProjectId(p.getId().toString())
        .setConfirmed(true)
        .setConfirmedProjectKey(confirmed)
        .setMutation(
            ru.taska.api.b2b.v1.MutationOptions.newBuilder()
                .setIdempotencyKey(key.toString())
                .setExpectedVersion(Integer.toString(version)))
        .build();

}

  @Test
  void deletionAdmissionIsVersionedScopedAndReplayDoesNotDuplicateIntent() {
    var p = create(A, "DELETE");
    var b = create(B, "DELETE");
    var owners = org.mockito.Mockito.mock(ProjectDeletions.Owners.class);
    var api = lifecycleApi(true, true);
    var deletion = new ProjectDeletions(store, api, owners);
    var key = UUID.randomUUID();
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                DomainException.class,
                () ->
                    as(
                            A,
                            ACTOR,
                            deletion.start(deleteRequest(p, key, p.getVersion() + 1, "DELETE")))
                        .block(WAIT))
            .getDetails()
            .get("code"));
    assertEquals(
        "CONFIRMATION_REQUIRED",
        assertThrows(
                DomainException.class,
                () ->
                    as(A, ACTOR, deletion.start(deleteRequest(p, key, p.getVersion(), "WRONG")))
                        .block(WAIT))
            .getDetails()
            .get("code"));
    assertEquals(
        DomainStatus.NOT_FOUND,
        assertThrows(
                DomainException.class,
                () ->
                    as(A, ACTOR, deletion.start(deleteRequest(b, key, b.getVersion(), "DELETE")))
                        .block(WAIT))
            .getStatus());
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.project_deletion_operations")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    var op =
        as(A, ACTOR, deletion.start(deleteRequest(p, key, p.getVersion(), "DELETE"))).block(WAIT);
    assertEquals(
        op,
        as(A, ACTOR, deletion.start(deleteRequest(p, key, p.getVersion(), "DELETE"))).block(WAIT));
    assertEquals(
        "PROJECT_DELETING",
        assertThrows(
                DomainException.class,
                () ->
                    as(
                            A,
                            ACTOR,
                            deletion.start(
                                deleteRequest(p, UUID.randomUUID(), p.getVersion(), "DELETE")))
                        .block(WAIT))
            .getDetails()
            .get("code"));
    assertEquals(1L, as(A, ACTOR, store.execute(q -> q.eventIds(p.getId()).count())).block(WAIT));
    assertThrows(
        Exception.class,
        () -> as(A, ACTOR, store.execute(q -> q.settings(p.getId(), "{}", ACTOR))).block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            as(
                    A,
                    ACTOR,
                    tx.execute(
                        db ->
                            db.sql("DELETE FROM taska.projects WHERE tenant_id=:t AND id=:p")
                                .bind("t", A)
                                .bind("p", p.getId())
                                .then()))
                .block(WAIT));
    assertNotNull(as(B, ACTOR, store.execute(q -> q.find(b.getId()))).block(WAIT));
    assertEquals(0, as(A, ACTOR, api.list(ACTOR.toString())).block(WAIT).getProjectResponseCount());
    assertEquals(1, as(B, ACTOR, api.list(ACTOR.toString())).block(WAIT).getProjectResponseCount());
    org.mockito.Mockito.verifyNoInteractions(owners);
  }

  @Test
  void deletionFailureProjectionDistinguishesIntegrityMissingAndTransientFailures() {
    var p = create(A, "ERRORS");
    var deletion =
        new ProjectDeletions(
            store,
            lifecycleApi(true, true),
            org.mockito.Mockito.mock(ProjectDeletions.Owners.class));
    var op =
        as(A, ACTOR, deletion.start(deleteRequest(p, UUID.randomUUID(), p.getVersion(), "ERRORS")))
            .block(WAIT);
    var codes =
        Map.of(
            "DATA_LOSS",
            "INVALID_STATE",
            "NOT_FOUND",
            "RESOURCE_NOT_FOUND",
            "UNAVAILABLE",
            "DEPENDENCY_UNAVAILABLE",
            "PERMISSION_DENIED",
            "SCOPE_DENIED");
    for (var code : codes.entrySet()) {
      boolean manual = Set.of("DATA_LOSS", "NOT_FOUND").contains(code.getKey());
      operator
          .sql(
              "UPDATE taska.project_deletion_operations SET last_code=:c,manual_review=:m WHERE"
                  + " id=:id")
          .bind("c", code.getKey())
          .bind("m", manual)
          .bind("id", UUID.fromString(op.getId()))
          .then()
          .block(WAIT);
      var projected = as(A, ACTOR, deletion.get(UUID.fromString(op.getId()))).block(WAIT);
      assertEquals("ERROR_CODE_" + code.getValue(), projected.getFailureCode().name());
      assertEquals(manual ? "STATE_MANUAL_REVIEW" : "STATE_RUNNING", projected.getState().name());
    }
  }

  @Test
  void deletionRecoveryRetainsIssueAckWhileWorkflowIsPending() {
    var p = create(A, "RESUME");
    var owners = org.mockito.Mockito.mock(ProjectDeletions.Owners.class);
    var deletion = new ProjectDeletions(store, lifecycleApi(true, true), owners);
    var op =
        as(A, ACTOR, deletion.start(deleteRequest(p, UUID.randomUUID(), p.getVersion(), "RESUME")))
            .block(WAIT);
    UUID id = UUID.fromString(op.getId());
    operator
        .sql("UPDATE taska.project_deletion_operations SET issue_done=true WHERE id=:id")
        .bind("id", id)
        .then()
        .block(WAIT);
    org.mockito.Mockito.when(owners.workflows(A, p.getId(), id))
        .thenReturn(Mono.just(false), Mono.just(true));
    org.mockito.Mockito.when(owners.removeProject(A, p.getId(), id)).thenReturn(Mono.empty());
    org.mockito.Mockito.when(owners.releaseQuota(A, p.getId(), id)).thenReturn(Mono.empty());
    var pending =
        assertThrows(
            DomainException.class,
            () ->
                ru.taska.security.tenant.ProjectTestContext.cleanup(A, deletion.recover(id))
                    .block(WAIT));
    assertEquals(DomainStatus.UNAVAILABLE, pending.getStatus());
    assertEquals("STATE_RUNNING", as(A, ACTOR, deletion.get(id)).block(WAIT).getState().name());
    assertEquals(
        "STATE_SUCCEEDED",
        ru.taska.security.tenant.ProjectTestContext.cleanup(A, deletion.recover(id))
            .block(WAIT)
            .getState()
            .name());
    ru.taska.security.tenant.ProjectTestContext.cleanup(A, deletion.recover(id)).block(WAIT);
    org.mockito.Mockito.verify(owners, org.mockito.Mockito.never()).issues(A, p.getId(), id);
    org.mockito.Mockito.verify(owners, org.mockito.Mockito.times(2)).workflows(A, p.getId(), id);
    org.mockito.Mockito.verify(owners).removeProject(A, p.getId(), id);
    org.mockito.Mockito.verify(owners).releaseQuota(A, p.getId(), id);
  }

  @Test
  void deletionReceiptFailureRollsBackAdmissionAndAudit() {
    var p = create(A, "ROLLBACK");
    var key = UUID.randomUUID();
    var deletion =
        new ProjectDeletions(
            store,
            lifecycleApi(true, true),
            org.mockito.Mockito.mock(ProjectDeletions.Owners.class));
    sql(
        "ALTER TABLE taska.project_deletion_operations ADD CONSTRAINT rejected_delete"
            + " CHECK(request_key<>'"
            + key
            + "'::uuid)");
    try {
      assertThrows(
          Exception.class,
          () ->
              as(A, ACTOR, deletion.start(deleteRequest(p, key, p.getVersion(), "ROLLBACK")))
                  .block(WAIT));
    } finally {
      sql("ALTER TABLE taska.project_deletion_operations DROP CONSTRAINT rejected_delete");
    }
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.project_deletion_operations")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.project_authorization_audit")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    as(A, ACTOR, store.execute(q -> q.settings(p.getId(), "{}", ACTOR))).block(WAIT);
  }
}
