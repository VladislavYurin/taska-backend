package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import nullable.NullableField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.*;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.*;
import ru.taska.domain.IssueType;
import ru.taska.domain.dto.IssuePatch;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.repository.WorkflowBindingReceiptRepository;
import ru.taska.service.IssueService;
import ru.taska.service.WorkflowBindingMigrationService;
import ru.taska.service.WorkflowIssueMigrationService;
import ru.taska.service.patch.IssuePatchExecutor;
import ru.taska.service.transition.IssueTransitionService;
import ru.taska.transport.grpc.WorkflowBindingIssueMigrationGrpcService;
import ru.taska.transport.grpc.WorkflowIssueMigrationGrpcService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.kafka.OutboxEventScheduler;
import tools.jackson.databind.ObjectMapper;

/** Real PG ingress locks, status/history/outbox atomicity and transient fences; journal uses TCP gRPC. */
class WorkflowBindingMigrationIT extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(30);
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    @Autowired WorkflowBindingMigrationService migration;
    @Autowired WorkflowBindingReceiptRepository receipts;
    @Autowired WorkflowIssueMigrationService legacy;
    @Autowired IssueService creates;
    @Autowired IssuePatchExecutor patches;
    @Autowired IssueTransitionService transitions;
    @Autowired IssueRepository issues;
    @Autowired IssueWatcherRepository watchers;
    @Autowired DatabaseClient db;
    @Autowired ObjectMapper mapper;
    private UUID project,operation,actor,token,assignee,watcher,sourceWorkflow,targetWorkflow,transition;
    private final Map<String,UUID> generations=new java.util.LinkedHashMap<>();
    private final AtomicReference<WorkflowBindingIntent> journal=new AtomicReference<>();
    private final AtomicReference<List<ProjectIssueTypeResponse>> registry=new AtomicReference<>();
    private final AtomicReference<WorkflowResponse> resolved=new AtomicReference<>();
    private volatile boolean journalPending;
    private Server server;
    private ManagedChannel channel;
    private int number;

    @org.springframework.test.context.DynamicPropertySource
    static void port(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.grpc.server.address",()->"127.0.0.1:0");properties.add("spring.grpc.server.port",()->"0");
    }
    @BeforeEach
    void prepare() throws Exception {
        project=UUID.randomUUID();operation=UUID.randomUUID();actor=UUID.randomUUID();token=UUID.randomUUID();assignee=UUID.randomUUID();watcher=UUID.randomUUID();
        sourceWorkflow=UUID.randomUUID();targetWorkflow=UUID.randomUUID();transition=UUID.randomUUID();number=0;
        generations.clear();for(String code:List.of("TASK","BUG","STORY"))generations.put(code,UUID.randomUUID());
        journal.set(WorkflowBindingIntent.newBuilder().setProjectId(project.toString()).setOperationId(operation.toString()).setWorkflowId(targetWorkflow.toString())
                .setActorUserId(actor.toString()).setState("PENDING").setOriginalHeader(Header.newBuilder().setRequestId("original").setNodeId("gateway")
                        .setActionChannel("MCP").setTokenId(token.toString())).addMoves(move("TASK",sourceWorkflow,targetWorkflow,"TODO","DONE")).build());
        journalPending=true;registry.set(types(true));resolved.set(workflow(sourceWorkflow,1));
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenAnswer(c->Mono.defer(()->Mono.just(registry.get())));
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                .setProjectExists(true).setIsMember(true).setRole(ProjectRole.PROJECT_ROLE_ADMIN).setProjectArchived(false).build()));
        when(projects.getProjectKeyInternal(any(),any(),any())).thenReturn(Mono.just("P"+project.toString().replace("-","").substring(0,8).toUpperCase()));
        when(workflowStub.withDeadlineAfter(anyLong(),any())).thenReturn(workflowStub);
        when(workflowStub.getWorkflowForProject(any(GetWorkflowForProjectRequest.class))).thenAnswer(c->Mono.defer(()->Mono.just(resolved.get())));
        when(workflowStub.listProjectWorkflows(any(ListProjectWorkflowsRequest.class))).thenReturn(Mono.error(
                io.grpc.Status.RESOURCE_EXHAUSTED.withDescription("Aggregate workflow graphs exceed 4 MiB").asRuntimeException()));
        when(workflowStub.checkProjectWorkflowScope(any(CheckProjectWorkflowScopeRequest.class))).thenAnswer(c->Mono.defer(()->{
            CheckProjectWorkflowScopeRequest request=c.getArgument(0);
            if(request.hasExpectedWorkflowId()&&(!request.getExpectedWorkflowId().equals(resolved.get().getId())
                    ||request.getExpectedWorkflowVersion()!=resolved.get().getVersion())) {
                return Mono.error(new DomainException(DomainStatus.ABORTED,"Workflow changed",Map.of("reason","WORKFLOW_CHANGED")));
            }
            if(request.hasStatusKey()&&resolved.get().getStatusesList().stream().noneMatch(status->status.getStatusKey().equals(request.getStatusKey()))) {
                return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,"Initial status invalid",Map.of("reason","STATUS_NOT_IN_WORKFLOW")));
            }
            return Mono.just(com.google.protobuf.Empty.getDefaultInstance());
        }));
        when(workflowStub.validateTransition(any(ValidateTransitionRequest.class))).thenReturn(Mono.just(validTransition("REVIEW")));
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0))
                .addService(new ReactorWorkflowBindingJournalGrpc.WorkflowBindingJournalImplBase() {
                    @Override public Mono<WorkflowBindingIntent> get(Mono<TypeOperationKey> request) {return request.map(key->journal.get());}
                    @Override public Mono<WorkflowBindingIntent> getPending(Mono<TypeOperationProjectRequest> request) {
                        return request.map(key->journalPending?journal.get():WorkflowBindingIntent.getDefaultInstance());
                    }
                }).addService(new WorkflowBindingIssueMigrationGrpcService(migration)).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        when(workflowStub.getChannel()).thenReturn(channel);
    }
    @AfterEach void close() throws Exception {
        if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
    }

    @Test
    void atomicallyChangesOnlyNeededStatusesWithOriginalAttributionAndRecipientSnapshots() {
        Issue old=issue("TASK","REVIEW"),unchanged=issue("TASK","TODO"),identity=issue("STORY","OUTSIDE");
        journal.set(journal.get().toBuilder().addMoves(move("STORY",targetWorkflow,targetWorkflow,"TODO")).putStatusMappings("REVIEW","DONE").putStatusMappings("TODO","TODO").build());
        registry.set(types(true));watchers.insertIfAbsent(old.getId(),project,watcher,actor).block(TIMEOUT);
        assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isEqualTo(1);
        Issue saved=issues.findById(old.getId()).block(TIMEOUT);assertThat(saved.getStatusKey()).isEqualTo("DONE");
        assertThat(saved.getIssueType()).isEqualTo(IssueType.TASK);assertThat(saved.getVersion()).isEqualTo(old.getVersion()+1);
        assertThat(saved.getAssigneeId()).isEqualTo(assignee);
        assertThat(issues.findById(unchanged.getId()).block(TIMEOUT).getVersion()).isEqualTo(unchanged.getVersion());
        assertThat(issues.findById(identity.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("OUTSIDE");
        assertThat(count("issue_history","issue_id",unchanged.getId())).isZero();assertThat(count("issue_history","issue_id",identity.getId())).isZero();
        var event=db.sql("SELECT actor_user_id,channel,token_id,payload::text AS p,event_type FROM taska.issue_history WHERE issue_id=:id")
                .bind("id",old.getId()).fetch().one().block(TIMEOUT);
        assertThat(event.get("event_type")).isEqualTo("TRANSITIONED");assertThat(event.get("actor_user_id")).isEqualTo(actor);
        assertThat(event.get("channel")).isEqualTo("MCP");assertThat(event.get("token_id")).isEqualTo(token);
        var payload=mapper.readTree(event.get("p").toString());assertThat(payload.path("reason").asText()).isEqualTo("WORKFLOW_CONFIGURATION_CHANGED");
        assertThat(payload.path("workflowId").asText()).isEqualTo(targetWorkflow.toString());assertThat(payload.path("assigneeId").asText()).isEqualTo(assignee.toString());
        assertThat(payload.path("watcherIds").toString()).contains(watcher.toString());assertThat(payload.has("operationId")).isFalse();
        var outbox=db.sql("SELECT event_type,payload::text AS p FROM taska.outbox_events WHERE aggregate_id=:id").bind("id",old.getId()).fetch().one().block(TIMEOUT);
        assertThat(outbox.get("event_type")).isEqualTo("IssueTransitioned");assertThat(outbox.get("p").toString()).doesNotContain(token.toString());
        assertThat(count("issue_history","issue_id",old.getId())).isEqualTo(1);assertThat(count("outbox_events","aggregate_id",old.getId())).isEqualTo(1);
    }

    @Test
    void distinctDestinationsWithEmptyCommonIntersectionStillAcceptTheirActualMappings() {
        issue("TASK","OLD_A");issue("BUG","OLD_B");
        journal.set(journal.get().toBuilder().clearMoves().addMoves(move("TASK",sourceWorkflow,targetWorkflow,"START"))
                .addMoves(move("BUG",UUID.randomUUID(),UUID.randomUUID(),"FINISH")).build());registry.set(types(true));
        StepVerifier.create(migration.apply(project,operation)).expectErrorSatisfies(error->{
            var failure=(DomainException)error;assertThat(failure.getDetails()).containsEntry("reason","WORKFLOW_ISSUES_UNMAPPED").containsEntry("issueCount","2").containsEntry("targetStatusKeys","");
            assertThat(mapper.readTree(failure.getDetails().get("targetStatusKeysByIssueType")).path("TASK").toString()).contains("START");
        }).verify(TIMEOUT);
        mappings(Map.of("OLD_A","START","OLD_B","FINISH","UNUSED","OTHER"));
        assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isEqualTo(2);
    }

    @Test
    void emptyDestinationAllowsZeroIssuesAndReportsLiveUnmappedIssues() {
        journal.set(journal.get().toBuilder().clearMoves().addMoves(move("TASK",sourceWorkflow,targetWorkflow)).build());registry.set(types(true));
        var row=issue("TASK","REVIEW");
        StepVerifier.create(migration.apply(project,operation)).expectErrorSatisfies(error->assertThat(((DomainException)error).getDetails())
                .containsEntry("reason","WORKFLOW_ISSUES_UNMAPPED").containsEntry("issueCount","1").containsEntry("targetStatusKeys","")).verify(TIMEOUT);
        db.sql("UPDATE taska.issues SET deleted_at=now() WHERE id=:id").bind("id",row.getId()).then().block(TIMEOUT);
        assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isZero();
    }

    @Test
    void capIsCombinedAcrossDestinationsAndExcludesValidIdentityMappings() {
        journal.set(journal.get().toBuilder().addMoves(move("BUG",sourceWorkflow,targetWorkflow,"TODO")).putStatusMappings("TODO","TODO").build());registry.set(types(true));
        bulk("TASK",6000,"'TODO'");
        assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isZero();
        terminalAndRelease();newOperation();
        journal.set(journal.get().toBuilder().clearMoves().addMoves(move("TASK",sourceWorkflow,targetWorkflow,"TODO"))
                .addMoves(move("BUG",sourceWorkflow,targetWorkflow,"TODO")).build());registry.set(types(true));
        bulk("TASK",2500,"'OLD'");bulk("BUG",2501,"'OLD'");
        rejected(migration.apply(project,operation),DomainStatus.FAILED_PRECONDITION,"WORKFLOW_MIGRATION_LIMIT");
        db.sql("UPDATE taska.issues SET deleted_at=now() WHERE id=(SELECT id FROM taska.issues WHERE project_id=:p AND issue_type='BUG' LIMIT 1)").bind("p",project).then().block(TIMEOUT);
        StepVerifier.create(migration.apply(project,operation)).expectErrorSatisfies(error->assertThat(((DomainException)error).getDetails())
                .containsEntry("reason","WORKFLOW_ISSUES_UNMAPPED").containsEntry("issueCount","5000")).verify(TIMEOUT);
    }

    @Test
    void actualInvalidTargetIsRejectedWithoutConsumingTheOperation() {
        issue("TASK","REVIEW");mappings(Map.of("REVIEW","INVALID"));
        rejected(migration.apply(project,operation),DomainStatus.INVALID_ARGUMENT,"WORKFLOW_STATUS_MAPPING_INVALID");
        assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
        mappings(Map.of("REVIEW","DONE"));assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackAllStatusesVersionsHistoryAndReceipt() {
        var a=issue("TASK","REVIEW");var b=issue("TASK","REVIEW");mappings(Map.of("REVIEW","DONE"));
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT fail_binding CHECK(payload->>'reason' IS DISTINCT FROM 'WORKFLOW_CONFIGURATION_CHANGED') NOT VALID").then().block(TIMEOUT);
        try {
            StepVerifier.create(migration.apply(project,operation)).expectError(org.springframework.dao.DataIntegrityViolationException.class).verify(TIMEOUT);
            for(var old:List.of(a,b)){var saved=issues.findById(old.getId()).block(TIMEOUT);assertThat(saved.getStatusKey()).isEqualTo("REVIEW");assertThat(saved.getVersion()).isEqualTo(old.getVersion());assertThat(count("issue_history","issue_id",old.getId())).isZero();}
            assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
        } finally {db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT fail_binding").then().block(TIMEOUT);}
        assertThat(migration.apply(project,operation).block(TIMEOUT).getChangedCount()).isEqualTo(2);
    }

    @Test
    void replaySkipsNewAclAndActiveFenceIsReleasedOnlyByTerminalJournalWithoutRetiringTheType() {
        var row=issue("TASK","REVIEW");mappings(Map.of("REVIEW","DONE"));var result=migration.apply(project,operation).block(TIMEOUT);
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Removed")));
        clearInvocations(projects);assertThat(migration.apply(project,operation).block(TIMEOUT)).isEqualTo(result);assertThat(migration.cancel(project,operation).block(TIMEOUT)).isEqualTo(result);verifyNoInteractions(projects);
        rejected(migration.release(project,operation),DomainStatus.FAILED_PRECONDITION,null);
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(true).setRole(ProjectRole.PROJECT_ROLE_ADMIN).setProjectArchived(false).build()));
        journal.set(journal.get().toBuilder().setState("COMPLETED").build());journalPending=false;registry.set(types(false));resolved.set(workflow(targetWorkflow,2));
        rejected(create("TODO"),DomainStatus.ABORTED,"WORKFLOW_BINDINGS_PENDING");
        migration.release(project,operation).block(TIMEOUT);
        assertThat(create("TODO").block(TIMEOUT).getIssueType()).isEqualTo(IssueType.TASK);
        assertThat(count("issue_history","issue_id",row.getId())).isEqualTo(1);
    }

    @Test
    void cancelledReceiptBlocksDelayedApplyAndDoesNotBlockFutureTypeUsage() {
        var row=issue("TASK","REVIEW");journal.set(journal.get().toBuilder().setState("CANCELLING").build());
        assertThat(migration.cancel(project,operation).block(TIMEOUT).getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        journal.set(journal.get().toBuilder().setState("PENDING").build());
        assertThat(migration.apply(project,operation).block(TIMEOUT).getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        journal.set(journal.get().toBuilder().setState("CANCELLED").build());migration.release(project,operation).block(TIMEOUT);journalPending=false;registry.set(types(false));
        assertThat(create("TODO").block(TIMEOUT)).isNotNull();assertThat(issues.findById(row.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("REVIEW");
    }

    @Test
    void pendingBlocksCreatePatchTransitionAndLegacyIngressWithoutDisclosingOperationId() {
        var row=issue("TASK","TODO");
        assertPending(create("TODO"));assertPending(patches.executePatch("r","n",row.getId(),actor,row.getVersion(),patch()));
        assertPending(transitions.transitionIssue("r","n",row.getId(),transition,actor,null,row.getVersion()));
        assertPending(legacy.migrate(project,actor,sourceWorkflow,List.of("TASK"),List.of("TODO"),Map.of(),"r","n"));
        assertThat(issues.findById(row.getId()).block(TIMEOUT).getVersion()).isEqualTo(row.getVersion());
    }

    @Test
    void transitionValidatedBeforeIntentDrainsUnder433AndMigrationSeesItsNewStatus() throws Exception {
        var row=issue("TASK","TODO");journalPending=false;registry.set(types(false));
        var entered=Sinks.empty();var release=Sinks.empty();var calls=new AtomicInteger();
        when(workflowStub.validateTransition(any(ValidateTransitionRequest.class))).thenAnswer(c->Mono.defer(()->{
            if(calls.incrementAndGet()==2){entered.tryEmitEmpty();return release.asMono().thenReturn(validTransition("REVIEW"));}
            return Mono.just(validTransition("REVIEW"));
        }));
        var moving=transitions.transitionIssue("r","n",row.getId(),transition,actor,null,row.getVersion()).subscribeOn(Schedulers.boundedElastic()).toFuture();
        entered.asMono().block(TIMEOUT);journalPending=true;registry.set(types(true));mappings(Map.of("REVIEW","DONE"));
        var applying=migration.apply(project,operation).subscribeOn(Schedulers.boundedElastic()).toFuture();
        assertThatThrownBy(()->applying.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.tryEmitEmpty();moving.get(20,TimeUnit.SECONDS);assertThat(applying.get(20,TimeUnit.SECONDS).getChangedCount()).isEqualTo(1);
        assertThat(issues.findById(row.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("DONE");assertThat(count("issue_history","issue_id",row.getId())).isEqualTo(2);
    }

    @Test
    void staleGenerationAndLateLegacyScopeAreRejectedAndMissingLegacyVersionFailsClosed() {
        var row=issue("TASK","REVIEW");generations.put("TASK",UUID.randomUUID());registry.set(types(true));
        rejected(migration.apply(project,operation),DomainStatus.ABORTED,"ISSUE_TYPES_CHANGED");
        journalPending=false;registry.set(types(false));resolved.set(workflow(targetWorkflow,2));
        var legacyRpc=new WorkflowIssueMigrationGrpcService(legacy);
        var command=ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest.newBuilder().setProjectId(project.toString()).setActorUserId(actor.toString())
                .setWorkflowId(sourceWorkflow.toString()).addIssueTypeKeys("TASK").addValidStatusKeys("TODO");
        rejected(legacyRpc.migrate(Mono.just(command.build())),DomainStatus.FAILED_PRECONDITION,"WORKFLOW_VERSION_REQUIRED");
        rejected(legacyRpc.migrate(Mono.just(command.setExpectedWorkflowVersion(1).build())),DomainStatus.ABORTED,"WORKFLOW_CHANGED");
        assertThat(issues.findById(row.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("REVIEW");
    }

    @Test
    void legacyIdentityVersionCheckDoesNotRequireAnyStatusOrVisibleTransitionOrAggregateGraphResponse() {
        journalPending=false;registry.set(types(false));
        resolved.set(WorkflowResponse.newBuilder().setId(sourceWorkflow.toString()).setVersion(1).build());
        var command=ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest.newBuilder().setProjectId(project.toString()).setActorUserId(actor.toString())
                .setWorkflowId(sourceWorkflow.toString()).setExpectedWorkflowVersion(1).addIssueTypeKeys("TASK").addValidStatusKeys("TODO").build();
        assertThat(new WorkflowIssueMigrationGrpcService(legacy).migrate(Mono.just(command)).block(TIMEOUT).getMovedCount()).isZero();
        verify(workflowStub,never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
        verify(workflowStub,never()).listProjectWorkflows(any(ListProjectWorkflowsRequest.class));
    }

    @Test
    void omittedInitialTodoIsValidatedAgainstInheritedWorkflowAndExplicitHiddenEdgeStatusStillWorks() {
        journalPending=false;registry.set(types(false));
        resolved.set(WorkflowResponse.newBuilder().setId(targetWorkflow.toString()).setVersion(2)
                .addStatuses(WorkflowStatus.newBuilder().setStatusKey("START")).build());
        String key=UUID.randomUUID().toString();
        rejected(creates.createIssue("r","n",key,project,IssueType.TASK,"New",null,IssuePriority.MEDIUM,actor,null,null,null,null,null),
                DomainStatus.FAILED_PRECONDITION,"STATUS_NOT_IN_WORKFLOW");
        assertThat(db.sql("SELECT count(*) FROM taska.issues WHERE project_id=:p").bind("p",project).map(row->row.get(0,Long.class)).one().block(TIMEOUT)).isZero();
        assertThat(db.sql("SELECT count(*) FROM taska.idempotency_keys WHERE user_id=:actor AND key=:key").bind("actor",actor).bind("key",key)
                .map(row->row.get(0,Long.class)).one().block(TIMEOUT)).isZero();
        assertThat(db.sql("SELECT count(*) FROM taska.outbox_events WHERE payload->>'projectId'=:p").bind("p",project.toString())
                .map(row->row.get(0,Long.class)).one().block(TIMEOUT)).isZero();
        var created=create("START").block(TIMEOUT);assertThat(created.getStatusKey()).isEqualTo("START");
        verify(workflowStub,never()).getWorkflowForProject(any(GetWorkflowForProjectRequest.class));
        verify(workflowStub,never()).listProjectWorkflows(any(ListProjectWorkflowsRequest.class));
    }

    @Test
    void largeUnmappedDetailsUseActualGrpcMessageBody() {
        bulk("TASK",1200,"'OLD_'||n");
        var result=ReactorWorkflowBindingIssueMigrationGrpc.newReactorStub(channel).apply(client.TypeOperationClients.key(project,operation)).block(TIMEOUT);
        assertThat(result.getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_REJECTED);
        assertThat(result.getRejectionDetailsOrThrow("statusKeys").length()).isGreaterThan(8192);
        assertThat(result.getRejectionDetailsOrThrow("statusKeys").split(",")).hasSize(1200);
    }

    private WorkflowTypeMove move(String code,UUID from,UUID to,String... statuses) {return WorkflowTypeMove.newBuilder().setCode(code).setGeneration(generations.get(code).toString()).setSourceWorkflowId(from.toString()).setTargetWorkflowId(to.toString()).addAllTargetStatusKeys(List.of(statuses)).build();}
    private void mappings(Map<String,String> mapping){journal.set(journal.get().toBuilder().clearStatusMappings().putAllStatusMappings(mapping).build());}
    private List<ProjectIssueTypeResponse> types(boolean pending) {
        return generations.entrySet().stream().map(entry->ProjectIssueTypeResponse.newBuilder().setCode(entry.getKey()).setGeneration(entry.getValue().toString())
                .setPendingOperationId(pending&&journal.get().getMovesList().stream().anyMatch(m->m.getCode().equals(entry.getKey()))?operation.toString():"").build()).toList();
    }
    private WorkflowResponse workflow(UUID id,int version) {return WorkflowResponse.newBuilder().setId(id.toString()).setVersion(version).addStatuses(WorkflowStatus.newBuilder().setStatusKey("TODO")).addStatuses(WorkflowStatus.newBuilder().setStatusKey("REVIEW")).addStatuses(WorkflowStatus.newBuilder().setStatusKey("DONE")).build();}
    private ValidateTransitionResponse validTransition(String target) {return ValidateTransitionResponse.newBuilder().setBody(ValidateTransitionResponseBody.newBuilder().setIsValid(true).setToStatusKey(target)).build();}
    private Issue issue(String type,String status) {
        int n=++number;UUID id=UUID.randomUUID();
        db.sql("INSERT INTO taska.issues(id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id,assignee_id) VALUES(:id,:p,:n,:key,:type,'Move',:status,'MEDIUM',:actor,:assignee)")
                .bind("id",id).bind("p",project).bind("n",n).bind("key",project+"-"+n).bind("type",type).bind("status",status).bind("actor",actor).bind("assignee",assignee).then().block(TIMEOUT);
        counter();return issues.findById(id).block(TIMEOUT);
    }
    private void bulk(String type,int count,String statusSql) {
        int start=number;number+=count;
        db.sql("INSERT INTO taska.issues(project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id) SELECT :p,n+:start,:prefix||'-'||(n+:start),:type,'Bulk',"+statusSql+",'MEDIUM',:actor FROM generate_series(1,:count) AS n")
                .bind("p",project).bind("start",start).bind("prefix",project.toString()).bind("type",type).bind("actor",actor).bind("count",count).then().block(TIMEOUT);counter();
    }
    private void counter(){db.sql("INSERT INTO taska.project_counters(project_id,next_issue_number) VALUES(:p,:n) ON CONFLICT(project_id) DO UPDATE SET next_issue_number=GREATEST(taska.project_counters.next_issue_number,:n)").bind("p",project).bind("n",number+1).then().block(TIMEOUT);}
    private Mono<Issue> create(String status){return creates.createIssue("r","n",UUID.randomUUID().toString(),project,IssueType.TASK,"New",null,IssuePriority.MEDIUM,actor,null,null,null,null,null,status);}
    private IssuePatch patch(){return new IssuePatch(Optional.of("Changed"),Optional.empty(),NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),Optional.empty());}
    private void terminalAndRelease(){journal.set(journal.get().toBuilder().setState("COMPLETED").build());migration.release(project,operation).block(TIMEOUT);journalPending=false;registry.set(types(false));}
    private void newOperation(){operation=UUID.randomUUID();journal.set(journal.get().toBuilder().setOperationId(operation.toString()).setState("PENDING").clearStatusMappings().build());journalPending=true;registry.set(types(true));}
    private long count(String table,String column,UUID id){return db.sql("SELECT count(*) AS n FROM taska."+table+" WHERE "+column+"=:id").bind("id",id).map(r->r.get("n",Long.class)).one().block(TIMEOUT);}
    private void assertPending(Mono<?> work){StepVerifier.create(work).expectErrorSatisfies(error->assertThat(((DomainException)error).getDetails()).containsEntry("reason","WORKFLOW_BINDINGS_PENDING").doesNotContainKey("operationId")).verify(TIMEOUT);}
    private void rejected(Mono<?> work,DomainStatus status,String reason){StepVerifier.create(work).expectErrorSatisfies(error->{assertThat(error).isInstanceOf(DomainException.class);var failure=(DomainException)error;assertThat(failure.getStatus()).isEqualTo(status);if(reason!=null)assertThat(failure.getDetails()).containsEntry("reason",reason);}).verify(TIMEOUT);}
}
