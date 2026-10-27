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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.domain.dto.IssuePatch;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueTypeMoveReceiptRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.IssueService;
import ru.taska.service.IssueTypeMoveService;
import ru.taska.service.IssueTypeWriteGuard;
import ru.taska.service.patch.IssuePatchExecutor;
import ru.taska.transport.grpc.IssueTypeIssueMigrationGrpcService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.workflow.GrpcWorkflowServiceClient;
import ru.taska.transport.kafka.OutboxEventScheduler;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL transactions, receipts, events and ingress locks; journal/pin use actual gRPC. */
class IssueTypeMoveIT extends AbstractIT {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ru.taska.service.WorkflowBindingPendingGuard workflowBindingGuard;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean GrpcWorkflowServiceClient workflows;
    @MockitoBean ReactorProjectServiceGrpc.ReactorProjectServiceStub projectStub;
    @MockitoBean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    @Autowired IssueTypeMoveService moves;
    @Autowired IssueTypeWriteGuard guard;
    @Autowired IssueTypeMoveReceiptRepository receipts;
    @Autowired IssueService creates;
    @Autowired IssuePatchExecutor patches;
    @Autowired IssueRepository issues;
    @Autowired IssueWatcherRepository watchers;
    @Autowired DatabaseClient db;
    @Autowired ObjectMapper mapper;
    private UUID project, operation, sourceGeneration, targetGeneration, actor, token, assignee, watcher;
    private final AtomicReference<IssueTypeDeletionIntent> intent = new AtomicReference<>();
    private final AtomicReference<List<ProjectIssueTypeResponse>> registry = new AtomicReference<>();
    private final AtomicReference<List<String>> valid = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;
    private int number;

    @org.springframework.test.context.DynamicPropertySource
    static void grpcPort(org.springframework.test.context.DynamicPropertyRegistry p) {
        p.add("spring.grpc.server.port", () -> "0");
    }

    @BeforeEach
    void prepare() throws Exception {
        org.mockito.Mockito.when(workflowBindingGuard.requireWritable(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.empty());
        project=UUID.randomUUID(); operation=UUID.randomUUID(); sourceGeneration=UUID.randomUUID(); targetGeneration=UUID.randomUUID();
        actor=UUID.randomUUID(); token=UUID.randomUUID(); assignee=UUID.randomUUID(); watcher=UUID.randomUUID(); number=0;
        valid.set(List.of("TODO","DONE"));
        intent.set(IssueTypeDeletionIntent.newBuilder().setOperationId(operation.toString()).setProjectId(project.toString())
                .setSourceCode("REQUEST").setSourceGeneration(sourceGeneration.toString()).setTargetCode("BUG")
                .setTargetGeneration(targetGeneration.toString()).setActorUserId(actor.toString()).setState("PENDING")
                .setOriginalHeader(Header.newBuilder().setRequestId("original-request").setNodeId("original-node")
                        .setActionChannel("MCP").setTokenId(token.toString())).build());
        registry.set(types(true));
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenAnswer(c -> Mono.defer(() -> Mono.just(registry.get())));
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(role(false,true)));
        when(projects.getProjectKeyInternal(any(),any(),any())).thenReturn(Mono.just("MV"+project.toString().substring(0,8).toUpperCase(java.util.Locale.ROOT)));
        when(workflows.requireStatusForType(any(),any(),any(),any())).thenReturn(Mono.empty());
        when(workflows.requireInitialStatus(any(),any(),any(),any(),any(),any())).thenReturn(Mono.empty());
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0))
                .addService(new ReactorIssueTypeDeletionJournalGrpc.IssueTypeDeletionJournalImplBase() {
                    @Override public Mono<IssueTypeDeletionIntent> getIntent(Mono<IssueTypeDeletionKey> request) {
                        return request.map(key -> intent.get());
                    }
                }).addService(new ReactorIssueTypeWorkflowReservationGrpc.IssueTypeWorkflowReservationImplBase() {
                    @Override public Mono<IssueTypeMoveWorkflowSnapshot> get(Mono<IssueTypeDeletionKey> request) {
                        return request.map(key -> IssueTypeMoveWorkflowSnapshot.newBuilder().setOperationId(operation.toString())
                                .setProjectId(project.toString()).setWorkflowId(UUID.randomUUID().toString())
                                .addAllValidStatusKeys(valid.get()).build());
                    }
                }).addService(new IssueTypeIssueMigrationGrpcService(moves)).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        when(projectStub.getChannel()).thenReturn(channel);
        when(workflowStub.getChannel()).thenReturn(channel);
    }

    @AfterEach
    void close() throws Exception {
        if(channel!=null) channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null) server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
    }

    @Test
    void movesAllLiveRowsWithOneVersionHistoryAndNotificationAndOriginalAttribution() {
        Issue mapped=issue("TRIAGE",false), unchanged=issue("TODO",false), unplaced=issue("ORPHAN",false), deleted=issue("TRIAGE",true);
        mappings(Map.of("TRIAGE","DONE","ORPHAN","TODO"));
        watchers.insertIfAbsent(mapped.getId(),project,watcher,actor).block(TIMEOUT);
        var result=moves.apply(project,operation).block(TIMEOUT);
        assertThat(result.getMovedCount()).isEqualTo(3);
        for(Issue old:List.of(mapped,unchanged,unplaced)) {
            Issue saved=issues.findById(old.getId()).block(TIMEOUT);
            assertThat(saved.getIssueType()).isEqualTo(IssueType.BUG);
            assertThat(saved.getVersion()).isEqualTo(old.getVersion()+1);
            assertThat(saved.getAssigneeId()).isEqualTo(assignee);
            assertThat(count("issue_history","issue_id",old.getId())).isEqualTo(1);
            assertThat(count("outbox_events","aggregate_id",old.getId())).isEqualTo(1);
            var history=db.sql("SELECT actor_user_id,channel,token_id,payload::text AS payload FROM taska.issue_history WHERE issue_id=:id")
                    .bind("id",old.getId()).fetch().one().block(TIMEOUT);
            assertThat(history.get("actor_user_id")).isEqualTo(actor);
            assertThat(history.get("channel")).isEqualTo("MCP");
            assertThat(history.get("token_id")).isEqualTo(token);
            var payload=mapper.readTree(history.get("payload").toString());
            assertThat(payload.path("oldIssueType").asText()).isEqualTo("REQUEST");
            assertThat(payload.path("newIssueType").asText()).isEqualTo("BUG");
            assertThat(payload.path("assigneeId").asText()).isEqualTo(assignee.toString());
            assertThat(payload.has("operationId")).isFalse();
            var outbox=db.sql("SELECT event_type,payload::text AS payload FROM taska.outbox_events WHERE aggregate_id=:id")
                    .bind("id",old.getId()).fetch().one().block(TIMEOUT);
            assertThat(outbox.get("event_type")).isEqualTo("IssueUpdated");
            assertThat(outbox.get("payload").toString()).contains("MCP").doesNotContain(token.toString());
        }
        assertThat(mapper.readTree(db.sql("SELECT payload::text AS p FROM taska.outbox_events WHERE aggregate_id=:id")
                .bind("id",mapped.getId()).map(r->r.get("p",String.class)).one().block(TIMEOUT))
                .path("watcherIds").toString()).contains(watcher.toString());
        assertThat(issues.findById(mapped.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("DONE");
        assertThat(issues.findById(unplaced.getId()).block(TIMEOUT).getStatusKey()).isEqualTo("TODO");
        assertThat(issues.findById(deleted.getId()).block(TIMEOUT).getIssueType().name()).isEqualTo("REQUEST");
        assertThat(count("issue_history","issue_id",deleted.getId())).isZero();
    }

    @Test
    void lostResponseReplayAndCancelAfterApplyReturnReceiptWithoutCurrentAclOrRegistry() {
        Issue row=issue("TODO",false);
        var first=moves.apply(project,operation).block(TIMEOUT);
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(role(true,false)));
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenReturn(Mono.error(new IllegalStateException("Registry unavailable")));
        clearInvocations(projects);
        assertThat(moves.apply(project,operation).block(TIMEOUT)).isEqualTo(first);
        assertThat(moves.cancel(project,operation).block(TIMEOUT)).isEqualTo(first);
        verifyNoInteractions(projects);
        assertThat(issues.findById(row.getId()).block(TIMEOUT).getVersion()).isEqualTo(row.getVersion()+1);
        assertThat(count("issue_history","issue_id",row.getId())).isEqualTo(1);
        assertThat(count("outbox_events","aggregate_id",row.getId())).isEqualTo(1);
    }

    @Test
    void failedOutboxRollsBackEveryIssueHistoryAndReceiptThenRetryAppliesOnce() {
        var a=issue("TODO",false); var b=issue("TODO",false);
        db.sql("ALTER TABLE taska.outbox_events ADD CONSTRAINT it_type_move_failure CHECK (payload->>'reason' IS DISTINCT FROM 'ISSUE_TYPE_DELETED') NOT VALID").then().block(TIMEOUT);
        try {
            StepVerifier.create(moves.apply(project,operation)).expectError(org.springframework.dao.DataIntegrityViolationException.class).verify(TIMEOUT);
            for(Issue old:List.of(a,b)) {
                assertThat(issues.findById(old.getId()).block(TIMEOUT).getIssueType().name()).isEqualTo("REQUEST");
                assertThat(count("issue_history","issue_id",old.getId())).isZero();
                assertThat(count("outbox_events","aggregate_id",old.getId())).isZero();
            }
            assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
        } finally {
            db.sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT it_type_move_failure").then().block(TIMEOUT);
        }
        assertThat(moves.apply(project,operation).block(TIMEOUT).getMovedCount()).isEqualTo(2);
        assertThat(count("issue_history","issue_id",a.getId())).isEqualTo(1);
    }

    @Test
    void allDistinctUnmappedStatusesSurviveActualGrpcAsMessageBodyAndNothingWrites() {
        bulk(1200,"'OLD_'||n",false);
        var result=ReactorIssueTypeIssueMigrationGrpc.newReactorStub(channel)
                .apply(client.IssueTypeDeletionClients.key(project,operation)).block(TIMEOUT);
        assertThat(result.getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_REJECTED);
        assertThat(result.getRejectionStatus()).isEqualTo("FAILED_PRECONDITION");
        assertThat(result.getRejectionDetailsMap().get("reason")).isEqualTo("ISSUE_STATUSES_UNMAPPED");
        assertThat(result.getRejectionDetailsMap().get("statusKeys").split(",")).hasSize(1200);
        assertThat(result.getRejectionDetailsMap().get("statusKeys").length()).isGreaterThan(8192);
        assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
        assertThat(typeCount("REQUEST")).isEqualTo(1200);
    }

    @Test
    void invalidMappingAndCapIncludeAlreadyValidStatusesButExcludeDeletedRows() {
        bulk(5000,"'TODO'",false); bulk(1,"'TODO'",true);
        mappings(Map.of("OTHER","INVALID"));
        rejected(moves.apply(project,operation),DomainStatus.INVALID_ARGUMENT,"ISSUE_TYPE_STATUS_MAPPING_INVALID");
        mappings(Map.of()); bulk(1,"'TODO'",false);
        rejected(moves.apply(project,operation),DomainStatus.FAILED_PRECONDITION,"WORKFLOW_MIGRATION_LIMIT");
        assertThat(typeCount("REQUEST")).isEqualTo(5001);
        assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
    }

    @Test
    void legacyDeleteCountsAllLiveRowsAndEmptyDeleteCreatesReceiptWithoutEvents() {
        var row=issue("ORPHAN",false); issue("TODO",true);
        intent.set(intent.get().toBuilder().clearTargetCode().clearTargetGeneration().build());
        StepVerifier.create(moves.apply(project,operation)).expectErrorSatisfies(error->{
            var failure=(DomainException)error;
            assertThat(failure.getDetails()).containsEntry("reason","ISSUE_TYPE_IN_USE").containsEntry("issueCount","1");
        }).verify(TIMEOUT);
        db.sql("UPDATE taska.issues SET deleted_at=now() WHERE id=:id").bind("id",row.getId()).then().block(TIMEOUT);
        assertThat(moves.apply(project,operation).block(TIMEOUT).getMovedCount()).isZero();
        assertThat(count("issue_history","issue_id",row.getId())).isZero();
    }

    @Test
    void pendingAndRetiredGenerationsBlockIngressWithoutLeakingOperationIdAndReusedGenerationWorks() {
        var old=issue("TODO",false);
        StepVerifier.create(create()).expectErrorSatisfies(error->assertThat(((DomainException)error).getDetails())
                .containsEntry("reason","ISSUE_TYPE_DELETION_PENDING").doesNotContainKey("operationId")).verify(TIMEOUT);
        rejected(patches.executePatch("r","n",old.getId(),actor,old.getVersion(),patch(IssueType.BUG)),
                DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_DELETION_PENDING");
        moves.apply(project,operation).block(TIMEOUT);
        registry.set(types(false));
        rejected(create(),DomainStatus.ABORTED,"ISSUE_TYPE_CHANGED");
        sourceGeneration=UUID.randomUUID(); registry.set(types(false));
        assertThat(create().block(TIMEOUT).getIssueType().name()).isEqualTo("REQUEST");
    }

    @Test
    void patchCanRepairAnOrphanedSourceTypeButStillRequiresRegisteredTarget() {
        var row=issue("TODO",false);
        registry.set(List.of(types(false).get(1)));
        assertThat(patches.executePatch("r","n",row.getId(),actor,row.getVersion(),patch(IssueType.BUG))
                .block(TIMEOUT).issue().getIssueType()).isEqualTo(IssueType.BUG);
    }

    @Test
    void cancelBeforeApplyIsPermanentAndCreatesNoTombstoneForActiveGeneration() {
        issue("TODO",false);
        intent.set(intent.get().toBuilder().setState("CANCELLING").build());
        assertThat(moves.cancel(project,operation).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED);
        intent.set(intent.get().toBuilder().setState("PENDING").build());
        assertThat(moves.apply(project,operation).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED);
        registry.set(types(false));
        assertThat(create().block(TIMEOUT)).isNotNull();
        assertThat(typeCount("BUG")).isZero();
    }

    @Test
    void staleRegistryGenerationAndRevokedOrArchivedActorCannotApplyWithoutReceipt() {
        issue("TODO",false);
        sourceGeneration=UUID.randomUUID(); registry.set(types(true));
        rejected(moves.apply(project,operation),DomainStatus.ABORTED,"ISSUE_TYPE_CHANGED");
        sourceGeneration=UUID.fromString(intent.get().getSourceGeneration()); registry.set(types(true));
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(role(false,false)));
        rejected(moves.apply(project,operation),DomainStatus.PERMISSION_DENIED,null);
        when(projects.checkProjectRole(any(),any(),any(),any())).thenReturn(Mono.just(role(true,true)));
        rejected(moves.apply(project,operation),DomainStatus.FAILED_PRECONDITION,"PROJECT_ARCHIVED");
        assertThat(receipts.find(project,operation).block(TIMEOUT)).isNull();
    }

    @Test
    void createValidatedBeforeIntentDrainsBeforeMoveAndNewCreatesCannotEnterPendingSource() throws Exception {
        registry.set(types(false));
        var entered=Sinks.empty(); var release=Sinks.empty();
        when(workflows.requireInitialStatus(any(),any(),any(),any(),any(),any())).thenAnswer(c -> Mono.defer(()->{
            entered.tryEmitEmpty(); return release.asMono().then();
        }));
        var creating=create().subscribeOn(Schedulers.boundedElastic()).toFuture();
        entered.asMono().block(TIMEOUT);
        registry.set(types(true));
        var applying=moves.apply(project,operation).subscribeOn(Schedulers.boundedElastic()).toFuture();
        assertThatThrownBy(()->applying.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.tryEmitEmpty();
        Issue created=creating.get(20,TimeUnit.SECONDS);
        assertThat(applying.get(20,TimeUnit.SECONDS).getMovedCount()).isEqualTo(1);
        assertThat(issues.findById(created.getId()).block(TIMEOUT).getIssueType()).isEqualTo(IssueType.BUG);
        rejected(create(),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_DELETION_PENDING");
        assertThat(typeCount("REQUEST")).isZero();
    }

    @Test
    void cancellationRacingAnAlreadyValidatedApplyObservesCommittedReceiptInsteadOfUndoingIt() throws Exception {
        Issue row=issue("TODO",false);
        var entered=Sinks.empty(); var release=Sinks.empty();
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenAnswer(call -> Mono.defer(()->{
            entered.tryEmitEmpty(); return release.asMono().thenReturn(registry.get());
        }));
        var applying=moves.apply(project,operation).subscribeOn(Schedulers.boundedElastic()).toFuture();
        entered.asMono().block(TIMEOUT);
        intent.set(intent.get().toBuilder().setState("CANCELLING").build());
        var cancelling=moves.cancel(project,operation).subscribeOn(Schedulers.boundedElastic()).toFuture();
        assertThatThrownBy(()->cancelling.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.tryEmitEmpty();
        var applied=applying.get(20,TimeUnit.SECONDS);
        assertThat(applied.getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED);
        assertThat(cancelling.get(20,TimeUnit.SECONDS)).isEqualTo(applied);
        assertThat(issues.findById(row.getId()).block(TIMEOUT).getIssueType()).isEqualTo(IssueType.BUG);
        assertThat(count("issue_history","issue_id",row.getId())).isEqualTo(1);
        assertThat(count("outbox_events","aggregate_id",row.getId())).isEqualTo(1);
    }

    @Test
    void concurrentApplyHasExactlyOneReceiptAndOnePairOfEventsPerIssue() {
        Issue row=issue("TODO",false);
        var results=Mono.zip(moves.apply(project,operation).subscribeOn(Schedulers.boundedElastic()),
                moves.apply(project,operation).subscribeOn(Schedulers.boundedElastic())).block(TIMEOUT);
        assertThat(results.getT1()).isEqualTo(results.getT2());
        assertThat(count("issue_history","issue_id",row.getId())).isEqualTo(1);
        assertThat(count("outbox_events","aggregate_id",row.getId())).isEqualTo(1);
    }

    private List<ProjectIssueTypeResponse> types(boolean pending) {
        return List.of(ProjectIssueTypeResponse.newBuilder().setCode("REQUEST").setGeneration(sourceGeneration.toString())
                        .setPendingOperationId(pending?operation.toString():"").build(),
                ProjectIssueTypeResponse.newBuilder().setCode("BUG").setGeneration(targetGeneration.toString())
                        .setPendingOperationId(pending?operation.toString():"").build());
    }
    private void mappings(Map<String,String> mappings) {intent.set(intent.get().toBuilder().clearStatusMappings().putAllStatusMappings(mappings).build());}
    private CheckProjectMemberRoleResponse role(boolean archived,boolean admin) {
        return CheckProjectMemberRoleResponse.newBuilder().setProjectExists(true).setIsMember(admin)
                .setRole(admin?ProjectRole.PROJECT_ROLE_ADMIN:ProjectRole.PROJECT_ROLE_MEMBER).setProjectArchived(archived).build();
    }
    private Issue issue(String status,boolean deleted) {
        int n=++number; UUID id=UUID.randomUUID();
        db.sql("INSERT INTO taska.issues(id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id,assignee_id,deleted_at) VALUES(:id,:project,:n,:key,'REQUEST','Move',:status,'MEDIUM',:actor,:assignee,CASE WHEN :deleted THEN now() ELSE NULL END)")
                .bind("id",id).bind("project",project).bind("n",n).bind("key",project+"-"+n).bind("status",status)
                .bind("actor",actor).bind("assignee",assignee).bind("deleted",deleted).then().block(TIMEOUT);
        db.sql("INSERT INTO taska.project_counters(project_id,next_issue_number) VALUES(:p,:n) ON CONFLICT(project_id) DO UPDATE SET next_issue_number=GREATEST(taska.project_counters.next_issue_number,:n)")
                .bind("p",project).bind("n",n+1).then().block(TIMEOUT);
        return issues.findById(id).block(TIMEOUT);
    }
    private void bulk(int count,String statusSql,boolean deleted) {
        int start=number; number+=count;
        db.sql("INSERT INTO taska.issues(project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id,deleted_at) SELECT :project,n+:start,:prefix||'-'||(n+:start),'REQUEST','Bulk',"+statusSql+",'MEDIUM',:actor,CASE WHEN :deleted THEN now() ELSE NULL END FROM generate_series(1,:count) AS n")
                .bind("project",project).bind("start",start).bind("prefix",project.toString()).bind("actor",actor)
                .bind("deleted",deleted).bind("count",count).then().block(TIMEOUT);
    }
    private Mono<Issue> create() {
        return creates.createIssue("r","n",UUID.randomUUID().toString(),project,IssueType.valueOf("REQUEST"),"New",null,
                IssuePriority.MEDIUM,actor,null,null,null,null,null,"TODO");
    }
    private IssuePatch patch(IssueType target) {
        return new IssuePatch(Optional.empty(),Optional.empty(),NullableField.absent(),NullableField.absent(),NullableField.absent(),
                NullableField.absent(),NullableField.absent(),NullableField.absent(),NullableField.absent(),Optional.of(target));
    }
    private long count(String table,String column,UUID id) {
        return db.sql("SELECT count(*) AS n FROM taska."+table+" WHERE "+column+"=:id").bind("id",id)
                .map(r->r.get("n",Long.class)).one().block(TIMEOUT);
    }
    private long typeCount(String type) {
        return db.sql("SELECT count(*) AS n FROM taska.issues WHERE project_id=:p AND issue_type=:type AND deleted_at IS NULL")
                .bind("p",project).bind("type",type).map(r->r.get("n",Long.class)).one().block(TIMEOUT);
    }
    private void rejected(Mono<?> operation,DomainStatus status,String reason) {
        StepVerifier.create(operation).expectErrorSatisfies(error->{
            assertThat(error).isInstanceOf(DomainException.class); var failure=(DomainException)error;
            assertThat(failure.getStatus()).isEqualTo(status);
            if(reason!=null) assertThat(failure.getDetails()).containsEntry("reason",reason);
        }).verify(TIMEOUT);
    }
}
