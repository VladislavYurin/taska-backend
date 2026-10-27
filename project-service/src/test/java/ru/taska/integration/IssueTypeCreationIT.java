package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.*;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.IssueTypeCreation;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.*;
import ru.taska.service.IssueTypeCreationCoordinator;
import ru.taska.service.IssueTypeCreationTransactions;
import ru.taska.service.IssueTypeDeletionTransactions;
import ru.taska.service.WorkflowTypeReservationTransactions;
import ru.taska.transport.kafka.OutboxEventScheduler;

/** Real project journal/registry transactions with PG-backed idempotent fake binding receipts over TCP. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.grpc.server.address=127.0.0.1:0","spring.grpc.server.port=0",
        "app.issue-type-creations.recovery-interval=1h","app.issue-type-deletions.recovery-interval=1h"})
class IssueTypeCreationIT extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(25);
    @MockitoBean OutboxEventScheduler scheduler;
    @MockitoBean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub;
    @Autowired IssueTypeCreationCoordinator coordinator;
    @Autowired IssueTypeCreationTransactions transactions;
    @Autowired IssueTypeCreationRepository creations;
    @Autowired ru.taska.service.ProjectIssueTypeService metadata;
    @Autowired ProjectIssueTypeRepository types;
    @Autowired ProjectTypeOperationRepository operations;
    @Autowired WorkflowTypeReservationTransactions reservations;
    @Autowired IssueTypeDeletionTransactions deletions;
    @Autowired DatabaseClient db;
    @Autowired ReactiveTransactionManager transactionManager;
    private UUID project,actor,token,mainWorkflow,explicitWorkflow,foreignWorkflow;
    private Header header;
    private Server server;
    private ManagedChannel channel;
    private final AtomicBoolean loseReply=new AtomicBoolean();
    private final AtomicReference<String> unknownRejection=new AtomicReference<>();
    private final Map<UUID,Header> observed=new ConcurrentHashMap<>();
    private volatile Sinks.One<Void> applyRelease;
    private volatile CountDownLatch applyHeld;

    @BeforeEach
    void prepare() throws Exception {
        project=UUID.randomUUID();actor=UUID.randomUUID();token=UUID.randomUUID();mainWorkflow=UUID.randomUUID();explicitWorkflow=UUID.randomUUID();foreignWorkflow=UUID.randomUUID();
        header=Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("gateway").setActionChannel("API_TOKEN").setTokenId(token.toString()).build();
        loseReply.set(false);unknownRejection.set(null);observed.clear();applyRelease=null;applyHeld=null;
        db.sql("CREATE TABLE IF NOT EXISTS taska.test_creation_binding_receipts(id uuid PRIMARY KEY,project_id uuid NOT NULL,state text NOT NULL,workflow uuid,code text,generation uuid)").then().block(TIMEOUT);
        db.sql("INSERT INTO taska.projects(id,project_key,name,created_by) VALUES(:p,:key,'Create type',:actor)").bind("p",project)
                .bind("key","P"+project.toString().replace("-","").substring(0,12).toUpperCase()).bind("actor",actor).then().block(TIMEOUT);
        db.sql("INSERT INTO taska.project_members(project_id,user_id,role,added_by) VALUES(:p,:actor,'ADMIN',:actor)").bind("p",project).bind("actor",actor).then().block(TIMEOUT);
        types.seedDefaults(project).block(TIMEOUT);types.syncLegacySettings(project,actor).block(TIMEOUT);
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new BindingPeer()).build().start();
        channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();when(workflowStub.getChannel()).thenReturn(channel);
    }
    @AfterEach void close() throws Exception {
        if(applyRelease!=null)applyRelease.tryEmitEmpty();
        if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        db.sql("UPDATE taska.project_issue_types SET pending_operation_id=NULL WHERE project_id=:p").bind("p",project).then()
                .then(db.sql("DELETE FROM taska.issue_type_creations WHERE project_id=:p").bind("p",project).then())
                .then(db.sql("DELETE FROM taska.issue_type_deletions WHERE project_id=:p").bind("p",project).then())
                .then(db.sql("DELETE FROM taska.project_type_operations WHERE project_id=:p").bind("p",project).then()).block(TIMEOUT);
    }

    @Test
    void reservesHiddenGenerationThenPublishesExactlyThatBindingAndMetadata() {
        var intent=begin("EPIC",null);
        assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();
        assertThat(operations.active(project).block(TIMEOUT).getOperationId()).isEqualTo(intent.id().toString());
        assertThat(intent.type().shape()).isEqualTo("square");assertThat(intent.type().icon()).isNull();
        assertThat(intent.type().name()).isEqualTo("Epic");assertThat(intent.firstTypeCode()).isEqualTo("TASK");
        var created=coordinator.drive(intent).block(TIMEOUT);
        assertThat(created.workflowId()).isEqualTo(mainWorkflow);assertThat(created.type().generation()).isEqualTo(intent.type().generation());
        assertThat(types.find(project,"EPIC").block(TIMEOUT).generation()).isEqualTo(intent.type().generation());
        assertThat(operations.active(project).block(TIMEOUT)).isNull();assertThat(creations.find(project,intent.id()).block(TIMEOUT).state()).isEqualTo("COMPLETED");
        assertThat(observed.get(intent.id())).isEqualTo(header);
        assertThat(db.sql("SELECT settings::text AS s FROM taska.project_settings WHERE project_id=:p").bind("p",project).map(row->row.get("s",String.class)).one().block(TIMEOUT)).contains("EPIC");
    }

    @Test
    void explicitWorkflowAndFirstRegistryTypeAreChosenUnderOneReservation() {
        types.reorder(project,java.util.List.of("BUG","TASK","STORY")).block(TIMEOUT);
        var intent=begin("EPIC",explicitWorkflow);
        assertThat(intent.firstTypeCode()).isEqualTo("BUG");assertThat(intent.firstTypeGeneration()).isEqualTo(types.find(project,"BUG").block(TIMEOUT).generation());
        assertThat(coordinator.drive(intent).block(TIMEOUT).workflowId()).isEqualTo(explicitWorkflow);
        var next=coordinator.create(project,actor,"MILESTONE","Milestone","amber","flag","diamond",40,null,newHeader()).block(TIMEOUT);
        assertThat(next.workflowId()).isEqualTo(mainWorkflow);assertThat(next.type().shape()).isEqualTo("diamond");assertThat(next.type().icon()).isEqualTo("flag");
    }

    @Test
    void confirmedBindingFailureLeavesNoTypeAndLateApplyCannotResurrectAfterRetry() {
        rejected(()->create("EPIC",foreignWorkflow),DomainStatus.INVALID_ARGUMENT,"WORKFLOW_NOT_IN_PROJECT");
        var old=latest();assertThat(old.state()).isEqualTo("CANCELLED");assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();
        assertThat(operations.active(project).block(TIMEOUT)).isNull();
        var late=ReactorWorkflowCreatedTypeBindingGrpc.newReactorStub(channel).apply(client.TypeOperationClients.key(project,old.id())).block(TIMEOUT);
        assertThat(late.getOutcome().getState()).isEqualTo(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED);
        header=newHeader();var created=create("EPIC",null);assertThat(created.type().generation()).isNotEqualTo(old.type().generation());
        assertThat(bindingCount()).isEqualTo(1);
    }

    @Test
    void lostBindingReplyRecoversForwardAfterAdminRemovalAndArchiveWithoutChangingAttribution() {
        loseReply.set(true);rejected(()->create("EPIC",null),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_CREATION_PENDING");
        var item=latest();assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();assertThat(bindingCount()).isEqualTo(1);
        db.sql("DELETE FROM taska.project_members WHERE project_id=:p AND user_id=:actor").bind("p",project).bind("actor",actor).then().block(TIMEOUT);
        db.sql("UPDATE taska.projects SET archived_at=now() WHERE id=:p").bind("p",project).then().block(TIMEOUT);
        coordinator.recover().block(TIMEOUT);
        var finished=creations.find(project,item.id()).block(TIMEOUT);assertThat(finished.state()).isEqualTo("COMPLETED");
        assertThat(finished.type().generation()).isEqualTo(item.type().generation());assertThat(finished.resolvedWorkflowId()).isEqualTo(mainWorkflow);
        assertThat(observed.get(item.id())).isEqualTo(header);assertThat(bindingCount()).isEqualTo(1);assertThat(operations.active(project).block(TIMEOUT)).isNull();
        rejected(()->create("EPIC",null),DomainStatus.PERMISSION_DENIED,null);
    }

    @Test
    void publishCommitFailureRollsBackRegistryButKeepsBindingReceiptAndRecoveryPublishesOnce() {
        db.sql("CREATE FUNCTION taska.fail_creation_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"+project+"'::uuid AND NEW.code='EPIC' THEN RAISE EXCEPTION 'publish fault'; END IF; RETURN NEW; END $$").then().block(TIMEOUT);
        db.sql("CREATE TRIGGER fail_creation_publish BEFORE INSERT ON taska.project_issue_types FOR EACH ROW EXECUTE FUNCTION taska.fail_creation_publish()").then().block(TIMEOUT);
        try{rejected(()->create("EPIC",null),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_CREATION_PENDING");}
        finally {db.sql("DROP TRIGGER fail_creation_publish ON taska.project_issue_types").then().then(db.sql("DROP FUNCTION taska.fail_creation_publish()").then()).block(TIMEOUT);}
        var item=latest();assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();assertThat(item.state()).isEqualTo("PENDING");
        assertThat(bindingCount()).isEqualTo(1);coordinator.recover().block(TIMEOUT);coordinator.recover().block(TIMEOUT);
        assertThat(types.find(project,"EPIC").block(TIMEOUT).generation()).isEqualTo(item.type().generation());assertThat(bindingCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdReplaysSavedResultFreshAclAndNewKeyPreservesDuplicateConflict() {
        var first=create("EPIC",null);assertThat(create("EPIC",null)).isEqualTo(first);
        rejected(()->coordinator.create(project,actor,"OTHER","Other","blue",null,null,null,null,header).block(TIMEOUT),DomainStatus.ALREADY_EXISTS,"REQUEST_ID_REUSED");
        header=newHeader();rejected(()->create("EPIC",null),DomainStatus.ALREADY_EXISTS,null);
        db.sql("UPDATE taska.project_members SET role='MEMBER' WHERE project_id=:p AND user_id=:actor").bind("p",project).bind("actor",actor).then().block(TIMEOUT);
        rejected(()->coordinator.create(project,actor,"EPIC"," Epic ","purple",null,null,null,null,
                observed.values().iterator().next()).block(TIMEOUT),DomainStatus.PERMISSION_DENIED,null);
        assertThat(bindingCount()).isEqualTo(1);
    }

    @Test
    void activeCreationAndDeletionShareOneProjectFenceAndReorderCannotChangeTheDefaultSnapshot() {
        var intent=begin("EPIC",null);
        rejected(()->deletions.begin(project,actor,"TASK",null,Map.of(),null,"delete","n",ActionMetadata.NONE).block(TIMEOUT),DomainStatus.ABORTED,"PROJECT_TYPE_OPERATION_PENDING");
        rejected(()->metadata.reorder(project,actor,java.util.List.of("BUG","TASK","STORY")).block(TIMEOUT),DomainStatus.ABORTED,"PROJECT_TYPE_OPERATION_PENDING");
        var existing=operations.active(project).block(TIMEOUT);assertThat(existing.getOperationId()).isEqualTo(intent.id().toString());
        assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isNull();
    }

    @Test
    void terminalProjectReleaseStoresAClosedReceiptBeforeADelayedReserveCanArrive() {
        UUID oldOperation=UUID.randomUUID();var base=workflowIntent(oldOperation,"PENDING");
        reservations.release(base.toBuilder().setState("CANCELLED").build()).block(TIMEOUT);
        rejected(()->reservations.reserve(base).block(TIMEOUT),DomainStatus.ABORTED,"PROJECT_TYPE_OPERATION_FINISHED");
        assertThat(operations.find(project,oldOperation).block(TIMEOUT).getActive()).isFalse();assertThat(types.find(project,"TASK").block(TIMEOUT).pendingOperationId()).isNull();
        var creating=begin("EPIC",null);reservations.release(base.toBuilder().setState("CANCELLED").build()).block(TIMEOUT);
        assertThat(operations.active(project).block(TIMEOUT).getOperationId()).isEqualTo(creating.id().toString());
    }

    @Test
    void pendingRequestWithSameKeyHasImmutableCommandAndNewKeyCannotStartAnotherCreation() {
        var first=begin("EPIC",null);assertThat(begin("EPIC",null).id()).isEqualTo(first.id());
        rejected(()->begin("EPIC",explicitWorkflow),DomainStatus.ALREADY_EXISTS,"REQUEST_ID_REUSED");
        Header other=newHeader();rejected(()->transactions.begin(project,actor,other.getRequestId(),other.getNodeId(),"OTHER","Other","blue",null,null,null,null,ActionMetadata.NONE).block(TIMEOUT),DomainStatus.ABORTED,"PROJECT_TYPE_OPERATION_PENDING");
        assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();
    }

    @Test
    void cancellationLosingToAppliedBindingReturnsCreatedDtoAndKeepsTheExactGeneration() throws Exception {
        var item=begin("EPIC",null);applyRelease=Sinks.one();applyHeld=new CountDownLatch(1);
        var applying=coordinator.drive(item).toFuture();assertThat(applyHeld.await(10,TimeUnit.SECONDS)).isTrue();
        var cancelling=transactions.requestCancellation(item,new DomainException(DomainStatus.ABORTED,"Concurrent cancel")).block(TIMEOUT);
        var cancellation=coordinator.drive(cancelling).toFuture();applyRelease.tryEmitEmpty();
        var first=applying.get(15,TimeUnit.SECONDS);var second=cancellation.get(15,TimeUnit.SECONDS);
        assertThat(first).isNotNull();assertThat(second).isNotNull();assertThat(second).isEqualTo(first);
        assertThat(first.type().generation()).isEqualTo(item.type().generation());assertThat(first.workflowId()).isEqualTo(mainWorkflow);
        assertThat(bindingCount()).isEqualTo(1);
    }

    @Test
    void unknownRemoteRejectionRemainsPendingAndCanRecoverWithoutACancellationReceipt() {
        unknownRejection.set("FUTURE_REMOTE_STATUS");
        rejected(()->create("EPIC",null),DomainStatus.UNAVAILABLE,"ISSUE_TYPE_CREATION_PENDING");
        var item=latest();assertThat(item.state()).isEqualTo("PENDING");assertThat(types.find(project,"EPIC").block(TIMEOUT)).isNull();
        assertThat(bindingCount()).isZero();unknownRejection.set(null);coordinator.recover().block(TIMEOUT);
        assertThat(creations.find(project,item.id()).block(TIMEOUT).state()).isEqualTo("COMPLETED");
        assertThat(types.find(project,"EPIC").block(TIMEOUT).generation()).isEqualTo(item.type().generation());assertThat(bindingCount()).isEqualTo(1);
    }

    private IssueTypeCreation begin(String code,UUID workflow){return transactions.begin(project,actor,header.getRequestId(),header.getNodeId(),code," Epic ","purple",null,null,null,workflow,new ActionMetadata(ActionChannel.API_TOKEN,token)).block(TIMEOUT);}
    private IssueTypeCreationTransactions.Created create(String code,UUID workflow){return coordinator.create(project,actor,code," Epic ","purple",null,null,null,workflow,header).contextWrite(context->action.GrpcActionContext.install(context,header)).block(TIMEOUT);}
    private Header newHeader(){return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("gateway").setActionChannel("API_TOKEN").setTokenId(token.toString()).build();}
    private IssueTypeCreation latest(){return creations.request(project,actor,header.getRequestId()).block(TIMEOUT);}
    private long bindingCount(){return db.sql("SELECT count(*) AS n FROM taska.test_creation_binding_receipts WHERE project_id=:p AND state='APPLIED'").bind("p",project).map(row->row.get("n",Long.class)).one().block(TIMEOUT);}
    private WorkflowBindingIntent workflowIntent(UUID op,String state){return WorkflowBindingIntent.newBuilder().setOperationId(op.toString()).setProjectId(project.toString()).setActorUserId(actor.toString()).setState(state).addMoves(WorkflowTypeMove.newBuilder().setCode("TASK").setGeneration(types.find(project,"TASK").block(TIMEOUT).generation().toString())).build();}
    private Mono<CreatedTypeBindingOutcome> receipt(UUID op){return db.sql("SELECT state,workflow FROM taska.test_creation_binding_receipts WHERE id=:id AND project_id=:p").bind("id",op).bind("p",project).map(row->{
        var result=CreatedTypeBindingOutcome.newBuilder().setOutcome(TypeOperationOutcome.newBuilder().setState(row.get("state",String.class).equals("APPLIED")?TypeOperationState.TYPE_OPERATION_STATE_APPLIED:TypeOperationState.TYPE_OPERATION_STATE_CANCELLED));
        UUID workflow=row.get("workflow",UUID.class);if(workflow!=null)result.setWorkflowId(workflow.toString());return result.build();}).one();}
    private Mono<Void> peerLock(UUID operation){return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:op,436))").bind("op",operation.toString()).map(row->true).one().then();}
    private Mono<CreatedTypeBindingOutcome> writeReceipt(IssueTypeCreation item,String state,UUID workflow){var sql=db.sql("INSERT INTO taska.test_creation_binding_receipts(id,project_id,state,workflow,code,generation) VALUES(:id,:p,:state,:workflow,:code,:generation)").bind("id",item.id()).bind("p",project).bind("state",state).bind("code",item.type().code()).bind("generation",item.type().generation());sql=workflow==null?sql.bindNull("workflow",UUID.class):sql.bind("workflow",workflow);return sql.then().then(receipt(item.id()));}
    private class BindingPeer extends ReactorWorkflowCreatedTypeBindingGrpc.WorkflowCreatedTypeBindingImplBase {
        @Override public Mono<CreatedTypeBindingOutcome> apply(Mono<TypeOperationKey> request){return request.flatMap(key->{UUID operation=UUID.fromString(key.getOperationId());
            var work=peerLock(operation).then(receipt(operation)).switchIfEmpty(Mono.defer(()->creations.find(project,operation).flatMap(item->{
                assertThat(item.state()).isEqualTo("PENDING");observed.putIfAbsent(operation,item.toProto().getOriginalHeader());
                if(unknownRejection.get()!=null)return Mono.just(CreatedTypeBindingOutcome.newBuilder().setOutcome(TypeOperationOutcome.newBuilder()
                        .setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED).setRejectionStatus(unknownRejection.get()).setRejectionMessage("Unknown future outcome")).build());
                if(foreignWorkflow.equals(item.desiredWorkflowId()))return Mono.just(CreatedTypeBindingOutcome.newBuilder().setOutcome(TypeOperationOutcome.newBuilder().setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED).setRejectionStatus("INVALID_ARGUMENT").setRejectionMessage("Foreign workflow").putRejectionDetails("reason","WORKFLOW_NOT_IN_PROJECT")).build());
                return db.sql("SELECT EXISTS(SELECT 1 FROM taska.project_members m JOIN taska.projects p ON p.id=m.project_id WHERE p.id=:p AND m.user_id=:actor AND m.role='ADMIN' AND p.archived_at IS NULL)").bind("p",project).bind("actor",actor).map(row->row.get(0,Boolean.class)).one().flatMap(allowed->{
                    if(!allowed)return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
                    return writeReceipt(item,"APPLIED",item.desiredWorkflowId()==null?mainWorkflow:item.desiredWorkflowId()).flatMap(outcome->applyRelease==null?Mono.just(outcome):Mono.fromRunnable(applyHeld::countDown).then(applyRelease.asMono()).thenReturn(outcome));
                });
            })));
            return TransactionalOperator.create(transactionManager).transactional(work).flatMap(outcome->outcome.getOutcome().getState()==TypeOperationState.TYPE_OPERATION_STATE_APPLIED&&loseReply.compareAndSet(true,false)?Mono.error(Status.UNAVAILABLE.withDescription("Reply lost after commit").asRuntimeException()):Mono.just(outcome));
        });}
        @Override public Mono<CreatedTypeBindingOutcome> cancel(Mono<TypeOperationKey> request){return request.flatMap(key->{UUID operation=UUID.fromString(key.getOperationId());
            return TransactionalOperator.create(transactionManager).transactional(peerLock(operation).then(receipt(operation)).switchIfEmpty(Mono.defer(()->creations.find(project,operation).flatMap(item->{assertThat(item.state()).isIn("CANCELLING","CANCELLED");return writeReceipt(item,"CANCELLED",null);}))));
        });}
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,DomainStatus status,String reason){assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,error->{assertThat(error.getStatus()).isEqualTo(status);if(reason!=null)assertThat(error.getDetails()).containsEntry("reason",reason);});}
}
