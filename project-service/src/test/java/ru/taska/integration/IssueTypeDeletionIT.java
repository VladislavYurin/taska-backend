package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeDeletion;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueTypeDeletionRepository;
import ru.taska.repository.ProjectIssueTypeRepository;
import ru.taska.service.IssueTypeDeletionCoordinator;
import ru.taska.service.IssueTypeDeletionTransactions;
import ru.taska.transport.kafka.OutboxEventScheduler;

/** Real project transactions and journal, with wire RPC peers whose receipts use PostgreSQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
        "spring.grpc.server.port=0", "app.issue-type-deletions.recovery-interval=1h"})
class IssueTypeDeletionIT extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(20);
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean ReactorIssueServiceGrpc.ReactorIssueServiceStub issueStub;
    @MockitoBean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    @Autowired IssueTypeDeletionTransactions transactions;
    @Autowired IssueTypeDeletionCoordinator coordinator;
    @Autowired IssueTypeDeletionRepository journal;
    @Autowired ProjectIssueTypeRepository types;
    private Server server;
    private ManagedChannel channel;
    private UUID project,actor,token;
    private Header original;
    private final AtomicBoolean loseApplyReply=new AtomicBoolean();
    private final AtomicInteger reserveCalls=new AtomicInteger(),releaseCalls=new AtomicInteger();
    private final AtomicReference<IssueTypeMoveOutcome> rejection=new AtomicReference<>();
    private final Set<UUID> reserved=ConcurrentHashMap.newKeySet();
    private final Map<UUID,Header> observedHeaders=new ConcurrentHashMap<>();
    private volatile Sinks.One<Void> holdApply;
    private volatile CountDownLatch applyHeld;

    @BeforeEach
    void prepare() throws Exception {
        project=UUID.randomUUID();actor=UUID.randomUUID();token=UUID.randomUUID();
        original=Header.newBuilder().setRequestId("initial-"+UUID.randomUUID()).setNodeId("original-gateway")
                .setActionChannel("MCP").setTokenId(token.toString()).build();
        loseApplyReply.set(false);reserveCalls.set(0);releaseCalls.set(0);
        rejection.set(null);reserved.clear();observedHeaders.clear();holdApply=null;applyHeld=null;
        db.sql("CREATE TABLE IF NOT EXISTS taska.test_433_peer_receipts(operation_id uuid PRIMARY KEY,project_id uuid NOT NULL,state text NOT NULL)")
                .then().block(TIMEOUT);
        db.sql("INSERT INTO taska.projects(id,project_key,name,created_by) VALUES(:id,:key,'Deletion test',:actor)")
                .bind("id",project).bind("key","P"+project.toString().replace("-","").substring(0,12).toUpperCase())
                .bind("actor",actor).then().block(TIMEOUT);
        member(actor,"ADMIN");
        types.seedDefaults(project).block(TIMEOUT);
        types.syncLegacySettings(project,actor).block(TIMEOUT);
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).directExecutor().addService(new IssuePeer()).addService(new WorkflowPeer()).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        when(issueStub.getChannel()).thenReturn(channel);
        when(workflowStub.getChannel()).thenReturn(channel);
    }

    @AfterEach
    void close() throws Exception {
        if(holdApply!=null)holdApply.tryEmitEmpty();
        if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        db.sql("UPDATE taska.project_issue_types SET pending_operation_id=NULL WHERE project_id=:p")
                .bind("p",project).then().then(db.sql("DELETE FROM taska.issue_type_deletions WHERE project_id=:p")
                        .bind("p",project).then())
                .then(db.sql("DELETE FROM taska.test_433_peer_receipts WHERE project_id=:p").bind("p",project).then())
                .then(db.sql("DELETE FROM taska.project_type_operations WHERE project_id=:p").bind("p",project).then())
                .then(db.sql("DELETE FROM taska.projects WHERE id=:p").bind("p",project).then()).block(TIMEOUT);
    }

    @Test
    void intentAndBothFencesCommitBeforeWireCallsAndRetriesPreserveOriginalAttribution() {
        UUID generation=types.find(project,"TASK").block(TIMEOUT).generation();
        Map<String,String> mapping=Map.of("TRIAGE","TODO");
        var item=begin("TASK","BUG",mapping,generation);
        assertThat(item.state()).isEqualTo("PENDING");
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isEqualTo(item.id());
        assertThat(types.find(project,"BUG").block(TIMEOUT).pendingOperationId()).isEqualTo(item.id());
        var retry=transactions.begin(project,actor,"TASK","BUG",mapping,generation,"different-request","different-node",ActionMetadata.NONE).block(TIMEOUT);
        assertThat(retry.id()).isEqualTo(item.id());
        assertThat(retry.requestId()).isEqualTo(original.getRequestId());
        assertThat(retry.attribution()).isEqualTo(new ActionMetadata(ActionChannel.MCP,token));
        rejected(() -> begin("TASK","STORY",Map.of(),generation),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_DELETION_PENDING");
        UUID member=UUID.randomUUID();member(member,"MEMBER");
        assertThatThrownBy(() -> transactions.begin(project,member,"TASK","BUG",mapping,null,"member","n",ActionMetadata.NONE).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,error->{
                    assertThat(error.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                    assertThat(error.getDetails()).doesNotContainKey("operationId");
                });
        coordinator.drive(item).block(TIMEOUT);
        assertCompleted(item.id());
        assertThat(observedHeaders.get(item.id())).isEqualTo(original);
        assertThat(appliedEffects()).isEqualTo(1);
        assertThat(journalCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"TASK","MISSING"})
    void targetMustBeAnotherRegisteredTypeAndValidationDoesNotLeaveAnIntent(String target) {
        rejected(() -> begin("TASK",target,Map.of(),null),DomainStatus.INVALID_ARGUMENT,"ISSUE_TYPE_TARGET_INVALID");
        assertThat(journalCount()).isZero();
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isNull();
    }

    @Test
    void lastTypeAndMalformedCommandsAreRejectedBeforeAnyRemoteEffect() {
        db.sql("DELETE FROM taska.project_issue_types WHERE project_id=:p AND code<>'TASK'").bind("p",project).then().block(TIMEOUT);
        rejected(() -> begin("TASK",null,Map.of(),null),DomainStatus.FAILED_PRECONDITION,"LAST_ISSUE_TYPE");
        rejected(() -> begin("TASK",null,Map.of("TRIAGE","TODO"),null),DomainStatus.INVALID_ARGUMENT,null);
        rejected(() -> begin("TASK","BUG",Map.of("bad key","TODO"),null),DomainStatus.INVALID_ARGUMENT,null);
        assertThat(journalCount()).isZero();assertThat(reserveCalls.get()).isZero();assertThat(appliedEffects()).isZero();
    }

    @Test
    void lostApplyReplyRecoversForwardAfterActorRemovalAndProjectArchive() {
        loseApplyReply.set(true);
        rejected(() -> delete("TASK","BUG",Map.of(),null),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_DELETION_PENDING");
        var item=journal.pending(project).block(TIMEOUT);
        assertThat(receipt(item.id()).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED);
        assertThat(types.find(project,"TASK").block(TIMEOUT)).isNotNull();
        db.sql("DELETE FROM taska.project_members WHERE project_id=:p AND user_id=:actor").bind("p",project).bind("actor",actor).then().block(TIMEOUT);
        db.sql("UPDATE taska.projects SET archived_at=now() WHERE id=:p").bind("p",project).then().block(TIMEOUT);
        coordinator.recover().block(TIMEOUT);
        assertCompleted(item.id());
        assertThat(appliedEffects()).isEqualTo(1);
        assertThat(observedHeaders.get(item.id())).isEqualTo(original);
        assertThat(releaseCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void finalizationRollbackKeepsIntentAndFencesAndRecoveryDoesNotApplyTwice() {
        installFinalizationFault();
        try {
            rejected(() -> delete("TASK","BUG",Map.of(),null),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_DELETION_PENDING");
        } finally { removeFinalizationFault(); }
        var item=journal.pending(project).block(TIMEOUT);
        assertThat(item).isNotNull();
        assertThat(item.state()).isEqualTo("PENDING");
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isEqualTo(item.id());
        assertThat(types.find(project,"BUG").block(TIMEOUT).pendingOperationId()).isEqualTo(item.id());
        assertThat(appliedEffects()).isEqualTo(1);
        coordinator.recover().block(TIMEOUT);
        coordinator.recover().block(TIMEOUT);
        assertCompleted(item.id());
        assertThat(appliedEffects()).isEqualTo(1);
    }

    @Test
    void cancellationLosesToAnApplyWhoseReceiptCommitsFirst() throws Exception {
        var item=begin("TASK","BUG",Map.of(),null);
        holdApply=Sinks.one();applyHeld=new CountDownLatch(1);
        var apply=coordinator.drive(item).toFuture();
        try {
            assertThat(applyHeld.await(10,TimeUnit.SECONDS)).isTrue();
            var cancelling=transactions.requestCancellation(item,new DomainException(DomainStatus.FAILED_PRECONDITION,"Concurrent reject")).block(TIMEOUT);
            assertThat(cancelling.state()).isEqualTo("CANCELLING");
            var cancel=coordinator.drive(cancelling).toFuture();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> db.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%hashtextextended%'")
                    .map(row -> row.get(0,Long.class)).one().block(TIMEOUT)>0);
            holdApply.tryEmitEmpty();
            apply.get(10,TimeUnit.SECONDS);cancel.get(10,TimeUnit.SECONDS);
            assertCompleted(item.id());
            assertThat(receipt(item.id()).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED);
            assertThat(appliedEffects()).isEqualTo(1);
        } finally { holdApply.tryEmitEmpty(); }
    }

    @Test
    void legacyInUseFailureCancelsDurablyAndLeavesTheSourceUsable() {
        rejection.set(IssueTypeMoveOutcome.newBuilder().setState(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_REJECTED)
                .setRejectionStatus("FAILED_PRECONDITION").setRejectionMessage("In use")
                .putRejectionDetails("reason","ISSUE_TYPE_IN_USE").putRejectionDetails("issueCount","7").build());
        rejected(() -> delete("TASK",null,Map.of(),null),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_IN_USE");
        var item=latest();
        assertThat(item.state()).isEqualTo("CANCELLED");assertThat(item.reservationReleased()).isTrue();
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isNull();
        assertThat(receipt(item.id()).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED);
        assertThat(item.errorDetails()).containsEntry("issueCount","7");
        assertThat(reserveCalls.get()).isZero();assertThat(appliedEffects()).isZero();
    }

    @Test
    void rejectedMoveReleasesBothFencesAndWorkflowPinBeforeCorrectedMappingStartsANewOperation() {
        rejection.set(IssueTypeMoveOutcome.newBuilder().setState(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_REJECTED)
                .setRejectionStatus("FAILED_PRECONDITION").setRejectionMessage("Statuses are unmapped")
                .putRejectionDetails("reason","ISSUE_STATUSES_UNMAPPED").putRejectionDetails("statusKeys","TRIAGE")
                .putRejectionDetails("targetStatusKeys","TODO,DONE").build());
        rejected(() -> delete("TASK","BUG",Map.of(),null),DomainStatus.FAILED_PRECONDITION,"ISSUE_STATUSES_UNMAPPED");
        var cancelled=latest();
        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(cancelled.reservationReleased()).isTrue();
        assertThat(cancelled.errorDetails()).containsEntry("statusKeys","TRIAGE").containsEntry("targetStatusKeys","TODO,DONE");
        assertThat(receipt(cancelled.id()).block(TIMEOUT).getState()).isEqualTo(IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED);
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isNull();
        assertThat(types.find(project,"BUG").block(TIMEOUT).pendingOperationId()).isNull();
        assertThat(reserveCalls.get()).isEqualTo(1);
        assertThat(releaseCalls.get()).isEqualTo(1);
        assertThat(appliedEffects()).isZero();

        rejection.set(null);
        delete("TASK","BUG",Map.of("TRIAGE","TODO"),null);
        var completed=latest();
        assertThat(completed.id()).isNotEqualTo(cancelled.id());
        assertCompleted(completed.id());
        assertThat(journalCount()).isEqualTo(2);
        assertThat(appliedEffects()).isEqualTo(1);
        assertThat(reserveCalls.get()).isEqualTo(2);
        assertThat(releaseCalls.get()).isEqualTo(2);
    }

    private void recreateTaskFixture() {
        types.insert(new ru.taska.domain.ProjectIssueType(project,"TASK","Task","blue",null,"square",10,null,null))
                .flatMap(saved -> types.syncLegacySettings(project,actor).thenReturn(saved)).block(TIMEOUT);
    }

    @Test
    void recreatedCodeRequiresCurrentGenerationForMoveButLegacyDeleteRemainsSupported() {
        UUID firstGeneration=types.find(project,"TASK").block(TIMEOUT).generation();
        delete("TASK","BUG",Map.of(),firstGeneration);
        var first=latest();
        delete("TASK","BUG",Map.of(),firstGeneration);
        assertThat(journalCount()).isEqualTo(1);
        recreateTaskFixture();
        UUID nextGeneration=types.find(project,"TASK").block(TIMEOUT).generation();
        assertThat(nextGeneration).isNotEqualTo(firstGeneration);
        rejected(() -> begin("TASK","BUG",Map.of(),null),DomainStatus.ABORTED,"ISSUE_TYPE_GENERATION_REQUIRED");
        rejected(() -> begin("TASK","BUG",Map.of(),firstGeneration),DomainStatus.ABORTED,"ISSUE_TYPE_CHANGED");
        var next=begin("TASK","BUG",Map.of(),nextGeneration);coordinator.drive(next).block(TIMEOUT);
        assertCompleted(next.id());
        assertThat(next.id()).isNotEqualTo(first.id());
        recreateTaskFixture();
        int reserves=reserveCalls.get();
        delete("TASK",null,Map.of(),null);
        assertThat(types.find(project,"TASK").block(TIMEOUT)).isNull();
        assertThat(reserveCalls.get()).isEqualTo(reserves);
        assertThat(journalCount()).isEqualTo(3);
    }

    @Test
    void legacyDeletionOfDefaultTypeSelectsTheNextRegisteredDefault() {
        db.sql("UPDATE taska.project_issue_types SET sort_order=CASE code WHEN 'BUG' THEN 0 WHEN 'TASK' THEN 10 ELSE 20 END WHERE project_id=:p")
                .bind("p",project).then().block(TIMEOUT);
        types.syncLegacySettings(project,actor).block(TIMEOUT);
        assertThat(defaultType()).isEqualTo("BUG");
        delete("BUG",null,Map.of(),null);
        assertThat(defaultType()).isEqualTo("TASK");
        assertThat(types.find(project,"BUG").block(TIMEOUT)).isNull();
        assertThat(types.list(project).map(type -> type.code()).collectList().block(TIMEOUT)).containsExactly("TASK","STORY");
        assertThat(latest().state()).isEqualTo("COMPLETED");
        assertThat(reserveCalls.get()).isZero();
    }

    @Test
    void concurrentLegacyDeletesCannotRemoveTheLastRegisteredType() throws Exception {
        db.sql("DELETE FROM taska.project_issue_types WHERE project_id=:p AND code='STORY'")
                .bind("p",project).then().block(TIMEOUT);
        Sinks.One<Void> start=Sinks.one();
        var task=coordinator.delete(project,actor,"TASK",null,Map.of(),null,original)
                .delaySubscription(start.asMono()).materialize().toFuture();
        var bug=coordinator.delete(project,actor,"BUG",null,Map.of(),null,original)
                .delaySubscription(start.asMono()).materialize().toFuture();
        start.tryEmitEmpty();
        var outcomes=Map.of("TASK",task.get(10,TimeUnit.SECONDS),"BUG",bug.get(10,TimeUnit.SECONDS));
        assertThat(outcomes.values().stream().filter(result -> result.isOnComplete()).count()).isEqualTo(1);
        assertThat(outcomes.values().stream().filter(result -> result.isOnError()).count()).isEqualTo(1);
        var failure=outcomes.entrySet().stream().filter(entry -> entry.getValue().isOnError()).findFirst().orElseThrow();
        assertThat(failure.getValue().getThrowable()).isInstanceOfSatisfying(DomainException.class,error -> {
            if(error.getStatus()==DomainStatus.UNAVAILABLE)
                assertThat(error.getDetails()).containsEntry("reason","ISSUE_TYPE_DELETION_PENDING");
            else {
                assertThat(error.getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                assertThat(error.getDetails()).containsEntry("reason","LAST_ISSUE_TYPE");
            }
        });
        rejected(() -> delete(failure.getKey(),null,Map.of(),null),DomainStatus.FAILED_PRECONDITION,"LAST_ISSUE_TYPE");
        assertThat(types.list(project).map(type -> type.code()).collectList().block(TIMEOUT)).containsExactly(failure.getKey());
        assertThat(defaultType()).isEqualTo(failure.getKey());
        assertThat(journalCount()).isEqualTo(1);
        assertThat(appliedEffects()).isEqualTo(1);
        assertThat(latest().state()).isEqualTo("COMPLETED");
        assertThat(reserveCalls.get()).isZero();
    }

    private String defaultType() {
        return db.sql("SELECT settings->>'defaultIssueType' FROM taska.project_settings WHERE project_id=:p")
                .bind("p",project).map(row -> row.get(0,String.class)).one().block(TIMEOUT);
    }

    private IssueTypeDeletion begin(String source,String target,Map<String,String> mapping,UUID generation) {
        return transactions.begin(project,actor,source,target,mapping,generation,original.getRequestId(),original.getNodeId(),
                new ActionMetadata(ActionChannel.MCP,token)).block(TIMEOUT);
    }
    private void delete(String source,String target,Map<String,String> mapping,UUID generation) {
        coordinator.delete(project,actor,source,target,mapping,generation,original)
                .contextWrite(context -> action.GrpcActionContext.install(context,original)).block(TIMEOUT);
    }
    private void member(UUID id,String role) {
        db.sql("INSERT INTO taska.project_members(project_id,user_id,role,added_by) VALUES(:p,:id,:role,:actor)")
                .bind("p",project).bind("id",id).bind("role",role).bind("actor",actor).then().block(TIMEOUT);
    }
    private void assertCompleted(UUID operation) {
        var item=journal.find(project,operation).block(TIMEOUT);
        assertThat(item.state()).isEqualTo("COMPLETED");assertThat(item.reservationReleased()).isTrue();
        assertThat(types.find(project,"TASK").block(TIMEOUT)).isNull();
        assertThat(types.find(project,"BUG").block(TIMEOUT).pendingOperationId()).isNull();
        String allowed=db.sql("SELECT (settings->'allowedIssueTypes')::text FROM taska.project_settings WHERE project_id=:p")
                .bind("p",project).map(row -> row.get(0,String.class)).one().block(TIMEOUT);
        assertThat(allowed).doesNotContain("TASK").contains("BUG");
    }
    private IssueTypeDeletion latest() {
        UUID id=db.sql("SELECT id FROM taska.issue_type_deletions WHERE project_id=:p ORDER BY created_at DESC,id DESC LIMIT 1")
                .bind("p",project).map(row -> row.get(0,UUID.class)).one().block(TIMEOUT);
        return journal.find(project,id).block(TIMEOUT);
    }
    private long journalCount() {
        return db.sql("SELECT count(*) FROM taska.issue_type_deletions WHERE project_id=:p").bind("p",project)
                .map(row -> row.get(0,Long.class)).one().block(TIMEOUT);
    }
    private long appliedEffects() {
        return db.sql("SELECT count(*) FROM taska.test_433_peer_receipts WHERE project_id=:p AND state='APPLIED'")
                .bind("p",project).map(row -> row.get(0,Long.class)).one().block(TIMEOUT);
    }
    private Mono<IssueTypeMoveOutcome> receipt(UUID operation) {
        return db.sql("SELECT state FROM taska.test_433_peer_receipts WHERE project_id=:p AND operation_id=:op")
                .bind("p",project).bind("op",operation).map(row -> outcome(row.get(0,String.class))).one();
    }
    private Mono<Void> peerLock(UUID operation) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:op,434))")
                .bind("op",operation.toString()).map(row -> true).one().then();
    }
    private Mono<IssueTypeMoveOutcome> insertReceipt(IssueTypeDeletion item,String state) {
        return db.sql("INSERT INTO taska.test_433_peer_receipts(operation_id,project_id,state) VALUES(:op,:p,:state)")
                .bind("op",item.id()).bind("p",item.projectId()).bind("state",state).then().thenReturn(outcome(state));
    }
    private IssueTypeMoveOutcome outcome(String state) {
        return IssueTypeMoveOutcome.newBuilder().setState(state.equals("APPLIED")
                ?IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED:IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_CANCELLED)
                .setMovedCount(state.equals("APPLIED")?1:0).build();
    }
    private Mono<Void> peerAuthorization(IssueTypeDeletion item) {
        return db.sql("SELECT p.archived_at IS NULL AND EXISTS(SELECT 1 FROM taska.project_members pm WHERE pm.project_id=p.id AND pm.user_id=:actor AND pm.role='ADMIN') AS allowed FROM taska.projects p WHERE p.id=:p")
                .bind("actor",item.actor()).bind("p",item.projectId()).map(row -> row.get(0,Boolean.class)).one()
                .flatMap(allowed -> allowed?Mono.empty():Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));
    }
    private class IssuePeer extends ReactorIssueTypeIssueMigrationGrpc.IssueTypeIssueMigrationImplBase {
        @Override public Mono<IssueTypeMoveOutcome> apply(Mono<IssueTypeDeletionKey> request) {
            return request.flatMap(key -> {
                UUID operation=UUID.fromString(key.getOperationId());
                Mono<IssueTypeMoveOutcome> work=peerLock(operation).then(receipt(operation)).switchIfEmpty(Mono.defer(() ->
                        journal.find(UUID.fromString(key.getProjectId()),operation).flatMap(item -> {
                            assertThat(item.state()).isEqualTo("PENDING");
                            return types.find(item.projectId(),item.source()).doOnNext(source ->
                                            assertThat(source.pendingOperationId()).isEqualTo(operation))
                                    .then(Mono.defer(() -> {
                                        if(item.target()!=null)assertThat(reserved).contains(operation);
                                        observedHeaders.putIfAbsent(operation,item.toProto().getOriginalHeader());
                                        if(rejection.get()!=null)return Mono.just(rejection.get());
                                        return peerAuthorization(item).then(insertReceipt(item,"APPLIED"))
                                                .flatMap(result -> holdApply==null?Mono.just(result):Mono.fromRunnable(applyHeld::countDown)
                                                        .then(holdApply.asMono()).thenReturn(result));
                                    }));
                        })));
                return TransactionalOperator.create(transactionManager).transactional(work).flatMap(result -> {
                    return result.getState()==IssueTypeMoveState.ISSUE_TYPE_MOVE_STATE_APPLIED&&loseApplyReply.compareAndSet(true,false)?Mono.error(Status.UNAVAILABLE.withDescription("Reply lost after commit").asRuntimeException()):Mono.just(result);
                });
            });
        }
        @Override public Mono<IssueTypeMoveOutcome> cancel(Mono<IssueTypeDeletionKey> request) {
            return request.flatMap(key -> {
                UUID operation=UUID.fromString(key.getOperationId());
                Mono<IssueTypeMoveOutcome> work=peerLock(operation).then(receipt(operation)).switchIfEmpty(Mono.defer(() ->
                        journal.find(UUID.fromString(key.getProjectId()),operation).flatMap(item -> {
                            assertThat(item.state()).isIn("CANCELLING","CANCELLED");
                            return insertReceipt(item,"CANCELLED");
                        })));
                return TransactionalOperator.create(transactionManager).transactional(work);
            });
        }
    }
    private class WorkflowPeer extends ReactorIssueTypeWorkflowReservationGrpc.IssueTypeWorkflowReservationImplBase {
        @Override public Mono<IssueTypeMoveWorkflowSnapshot> reserve(Mono<IssueTypeDeletionKey> request) {
            return request.flatMap(key -> journal.find(UUID.fromString(key.getProjectId()),UUID.fromString(key.getOperationId()))
                    .map(item -> {assertThat(item.state()).isEqualTo("PENDING");reserved.add(item.id());reserveCalls.incrementAndGet();
                        return IssueTypeMoveWorkflowSnapshot.newBuilder().setProjectId(item.projectId().toString()).setOperationId(item.id().toString())
                                .setWorkflowId(new UUID(0,1).toString()).addValidStatusKeys("TODO").addValidStatusKeys("DONE").build();}));
        }
        @Override public Mono<Empty> release(Mono<IssueTypeDeletionKey> request) {
            return request.flatMap(key -> journal.find(UUID.fromString(key.getProjectId()),UUID.fromString(key.getOperationId()))
                    .map(item -> {assertThat(item.state()).isIn("COMPLETED","CANCELLED");releaseCalls.incrementAndGet();return Empty.getDefaultInstance();}));
        }
    }
    private void installFinalizationFault() {
        db.sql("CREATE FUNCTION taska.reject_433_finalize() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.project_id='"+project+"'::uuid AND OLD.code='TASK' THEN RAISE EXCEPTION 'finalize fault'; END IF; RETURN OLD; END $$").then().block(TIMEOUT);
        db.sql("CREATE TRIGGER reject_433_finalize BEFORE DELETE ON taska.project_issue_types FOR EACH ROW EXECUTE FUNCTION taska.reject_433_finalize()").then().block(TIMEOUT);
    }
    private void removeFinalizationFault() {
        db.sql("DROP TRIGGER reject_433_finalize ON taska.project_issue_types").then().block(TIMEOUT);
        db.sql("DROP FUNCTION taska.reject_433_finalize()").then().block(TIMEOUT);
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,DomainStatus status,String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            if(reason!=null)assertThat(error.getDetails()).containsEntry("reason",reason);
        });
    }
}
