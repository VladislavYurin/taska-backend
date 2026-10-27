package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.registry.v1.ProjectTypeOperationGate;
import ru.taska.api.registry.v1.TypeOperationOutcome;
import ru.taska.api.registry.v1.TypeOperationState;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.WorkflowBindingChangeRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.service.IssueTypeWorkflowReservationService;
import ru.taska.service.ProjectWorkflowListService;
import ru.taska.service.WorkflowBindingCoordinator;
import ru.taska.service.WorkflowWriteFence;
import ru.taska.transport.grpc.issue.WorkflowBindingIssueClient;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

/** Real PostgreSQL journal/graph transactions; external receipt fakes inject lost replies and outages. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowBindingChangeIT extends AbstractIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(25);
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean ProjectRoleChecker roles;
    @MockitoBean WorkflowProjectOperationClient reservations;
    @MockitoBean WorkflowBindingIssueClient issues;
    @Autowired WorkflowBindingCoordinator coordinator;
    @Autowired ru.taska.service.WorkflowMutationService mutations;
    @Autowired WorkflowBindingChangeRepository journal;
    @Autowired WorkflowRepository workflows;
    @Autowired ProjectWorkflowListService listing;
    @Autowired WorkflowWriteFence fence;
    @Autowired IssueTypeWorkflowReservationService deletionPins;
    @Autowired WorkflowProperties properties;
    private UUID project, actor, target, donor, fallback;
    private int fallbackVersion;
    private Header header;
    private Map<String, UUID> generations;
    private final AtomicReference<ProjectTypeOperationGate> gate = new AtomicReference<>();
    private final Map<UUID, TypeOperationOutcome> receipts = new ConcurrentHashMap<>();
    private final AtomicInteger appliedCommits = new AtomicInteger();

    @DynamicPropertySource
    static void testConfiguration(DynamicPropertyRegistry registry) {
        registry.add("spring.grpc.server.address", () -> "127.0.0.1:0");
        registry.add("app.workflow-binding-changes.recovery-interval", () -> "1h");
    }

    @BeforeEach
    void prepare() {
        project=UUID.randomUUID(); actor=UUID.randomUUID(); target=UUID.randomUUID(); donor=UUID.randomUUID();
        header=header(); receipts.clear(); appliedCommits.set(0); gate.set(ProjectTypeOperationGate.getDefaultInstance());
        generations=List.of("TASK","BUG","STORY","EPIC").stream().collect(Collectors.toMap(k -> k,k -> UUID.randomUUID()));
        var registry=List.of("TASK","BUG","STORY","EPIC").stream().map(k -> ProjectIssueTypeResponse.newBuilder()
                .setCode(k).setName(k).setGeneration(generations.get(k).toString()).build()).toList();
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenReturn(Mono.just(registry));
        when(projects.getIssueTypesInternal(any())).thenReturn(Mono.just(registry));
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any())).thenReturn(Mono.empty());
        when(reservations.gate(any())).thenAnswer(call -> Mono.defer(() -> Mono.just(gate.get())));
        when(reservations.reserve(any(),any())).thenAnswer(call -> Mono.fromRunnable(() -> gate.set(
                ProjectTypeOperationGate.newBuilder().setOperationId(call.<UUID>getArgument(1).toString())
                        .setKind("WORKFLOW_BINDINGS").setActive(true).build())));
        when(reservations.release(any(),any())).thenAnswer(call -> Mono.fromRunnable(() -> {
            if (gate.get().getOperationId().equals(call.<UUID>getArgument(1).toString())) gate.set(ProjectTypeOperationGate.getDefaultInstance());
        }));
        normalApply();
        when(issues.cancel(any(),any())).thenAnswer(call -> Mono.fromSupplier(() -> receipts.computeIfAbsent(
                call.getArgument(1), key -> outcome(TypeOperationState.TYPE_OPERATION_STATE_CANCELLED))));
        when(issues.release(any(),any())).thenReturn(Mono.empty());
        var defaultGraph=workflows.findWorkflowForProject(properties.defaultProjectId(),"TASK").block(TIMEOUT);
        fallback=defaultGraph.getId(); fallbackVersion=defaultGraph.getVersion();
        graph(target,"Target",List.of("TODO","REVIEW","DONE")); graph(donor,"Donor",List.of("TODO","BACKLOG","DONE"));
        bind("BUG",target); bind("EPIC",donor);
    }

    @Test
    void mixedReplacementHasPerTypeDestinationsAndBumpsEachCustomGraphOnce() {
        var result=coordinator.replace(header,project,target,actor,1,null,List.of("TASK","EPIC"),Map.of()).block(TIMEOUT);
        var saved=journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT);
        assertThat(result.getWorkflow().getId()).isEqualTo(target.toString());
        assertThat(version(target)).isEqualTo(2); assertThat(version(donor)).isEqualTo(2);
        assertThat(version(fallback)).isEqualTo(fallbackVersion);
        assertThat(binding("TASK")).isEqualTo(target.toString()); assertThat(binding("EPIC")).isEqualTo(target.toString());
        assertThat(binding("BUG")).isNull();
        var moves=saved.plan().moves().stream().collect(Collectors.toMap(m -> m.code(),m -> m));
        assertThat(moves.get("BUG").targetWorkflow()).isEqualTo(fallback);
        assertThat(moves.get("BUG").targetStatuses()).contains("IN_PROGRESS");
        assertThat(moves.get("EPIC").targetStatuses()).contains("REVIEW").doesNotContain("IN_PROGRESS");
        assertThat(saved.state()).isEqualTo("COMPLETED"); assertThat(saved.released()).isTrue();
        assertThat(gate.get().getActive()).isFalse(); assertThat(appliedCommits).hasValue(1);
        var empty=listing.listProjectWorkflows("list","test",project,actor).block(TIMEOUT).stream()
                .filter(w -> w.workflow().workflow().getId().equals(donor)).findFirst().orElseThrow();
        assertThat(empty.issueTypes()).isEmpty(); assertThat(empty.shared()).isFalse();
        assertThat(empty.workflow().statuses()).hasSize(3);
    }

    @Test
    void rejectedMoveCancelsDurablyPreservesGraphAndFullDetailsThenCorrectedCommandSucceeds() {
        String keys=IntStream.range(0,1400).mapToObj(i -> "LEGACY_STATUS_"+i).collect(Collectors.joining(","));
        var rejection=TypeOperationOutcome.newBuilder().setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED)
                .setRejectionStatus("FAILED_PRECONDITION").setRejectionMessage("Map old statuses")
                .putRejectionDetails("reason","WORKFLOW_ISSUES_UNMAPPED").putRejectionDetails("issueCount","1400")
                .putRejectionDetails("statusKeys",keys).putRejectionDetails("targetStatusKeys","TODO,REVIEW,DONE").build();
        when(issues.apply(any(),any())).thenReturn(Mono.just(rejection));
        assertThatThrownBy(() -> replace(header,1)).isInstanceOfSatisfying(DomainException.class,error -> {
            assertThat(error.getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
            assertThat(error.getDetails()).containsEntry("statusKeys",keys);
        });
        var cancelled=journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT);
        assertThat(cancelled.state()).isEqualTo("CANCELLED"); assertThat(cancelled.released()).isTrue();
        assertThat(cancelled.errorDetails()).containsEntry("statusKeys",keys);
        assertThat(version(target)).isEqualTo(1); assertThat(version(donor)).isEqualTo(1);
        assertThat(binding("EPIC")).isEqualTo(donor.toString()); assertThat(gate.get().getActive()).isFalse();
        normalApply();
        assertThat(replace(header(),1).getWorkflow().getVersion()).isEqualTo(2);
        assertThat(appliedCommits).hasValue(1);
    }

    @Test
    void cancellationRacingAppliedReceiptReturnsForwardResultInsteadOfLosingTheValue() {
        when(issues.apply(any(),any())).thenAnswer(call -> Mono.defer(() -> {
            applied(call.getArgument(1));
            return Mono.error(new DomainException(DomainStatus.ABORTED,"A competing attempt already committed"));
        }));
        var result=replace(header,1);
        assertThat(result).isNotNull();
        assertThat(result.getWorkflow().getId()).isEqualTo(target.toString());
        var item=journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT);
        assertThat(item.state()).isEqualTo("COMPLETED"); assertThat(item.released()).isTrue();
        assertThat(binding("EPIC")).isEqualTo(target.toString());
        assertThat(version(target)).isEqualTo(2); assertThat(version(donor)).isEqualTo(2);
        assertThat(appliedCommits).hasValue(1);
        verify(issues).cancel(eq(project),eq(item.id()));
    }

    @Test
    void lostAppliedReplyRetriesTheSameReceiptAndDoesNotDuplicateVersions() {
        var first=new AtomicBoolean(true);
        when(issues.apply(any(),any())).thenAnswer(call -> Mono.defer(() -> {
            var committed=applied(call.getArgument(1));
            return first.getAndSet(false)?Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Lost reply")):Mono.just(committed);
        }));
        pending(() -> replace(header,1));
        assertThat(binding("EPIC")).isEqualTo(donor.toString()); assertThat(version(target)).isEqualTo(1);
        var result=replace(header,1);
        assertThat(replace(header,1)).isEqualTo(result);
        assertThat(version(target)).isEqualTo(2); assertThat(version(donor)).isEqualTo(2);
        assertThat(appliedCommits).hasValue(1); assertThat(receipts).hasSize(1);
    }

    @Test
    void finalizationRollbackKeepsPendingFenceAndRecoveryFinishesAfterActorRemoval() {
        sql("CREATE OR REPLACE FUNCTION taska.binding_it_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"IF NEW.id='"+target+"'::uuid THEN RAISE EXCEPTION 'injected workflow finalization failure'; END IF; RETURN NEW; END; $$");
        sql("CREATE TRIGGER binding_it_failure BEFORE UPDATE ON taska.workflows FOR EACH ROW EXECUTE FUNCTION taska.binding_it_failure()");
        try {
            pending(() -> replace(header,1));
            assertThat(version(target)).isEqualTo(1); assertThat(version(donor)).isEqualTo(1);
            assertThat(binding("EPIC")).isEqualTo(donor.toString()); assertThat(appliedCommits).hasValue(1);
            var item=journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT);
            assertThat(item.state()).isEqualTo("PENDING"); assertThat(item.released()).isFalse();
            assertThatThrownBy(() -> fence.requireReadable(project).block(TIMEOUT)).isInstanceOfSatisfying(DomainException.class,error ->
                    assertThat(error.getDetails()).containsEntry("reason","ISSUE_TYPE_OPERATION_PENDING").doesNotContainKey("operationId"));
        } finally {
            sql("DROP TRIGGER binding_it_failure ON taska.workflows"); sql("DROP FUNCTION taska.binding_it_failure()");
        }
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Actor removed")));
        coordinator.recover().block(TIMEOUT);
        assertThat(version(target)).isEqualTo(2); assertThat(version(donor)).isEqualTo(2);
        assertThat(binding("EPIC")).isEqualTo(target.toString()); assertThat(appliedCommits).hasValue(1);
        assertThat(gate.get().getActive()).isFalse();
        assertThatThrownBy(() -> replace(header,1)).isInstanceOfSatisfying(DomainException.class,error ->
                assertThat(error.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED));
    }

    @Test
    void failedReservationReleaseAfterCommitRecoversWithoutRepeatingMutation() {
        var first=new AtomicBoolean(true);
        when(reservations.release(any(),any())).thenAnswer(call -> Mono.defer(() -> {
            if (first.getAndSet(false)) return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Project unavailable"));
            gate.set(ProjectTypeOperationGate.getDefaultInstance()); return Mono.empty();
        }));
        pending(() -> replace(header,1));
        var item=journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT);
        assertThat(item.state()).isEqualTo("COMPLETED"); assertThat(item.released()).isFalse();
        assertThat(version(target)).isEqualTo(2); assertThat(gate.get().getActive()).isTrue();
        coordinator.recover().block(TIMEOUT);
        assertThat(version(target)).isEqualTo(2); assertThat(version(donor)).isEqualTo(2);
        assertThat(appliedCommits).hasValue(1); assertThat(gate.get().getActive()).isFalse();
        assertThat(journal.get(project,item.id()).block(TIMEOUT).released()).isTrue();
    }

    @Test
    void sameKeyChangedCommandAndNewKeyStaleVersionRejectWhileExactReplayReturnsSnapshot() {
        var result=replace(header,1);
        assertThatThrownBy(() -> coordinator.replace(header,project,target,actor,2,"Other",List.of("TASK"),Map.of()).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.ALREADY_EXISTS));
        assertThatThrownBy(() -> replace(header(),1)).isInstanceOfSatisfying(DomainException.class,error ->
                assertThat(error.getStatus()).isEqualTo(DomainStatus.ABORTED));
        assertThat(replace(header,1)).isEqualTo(result);
        assertThat(version(target)).isEqualTo(2); assertThat(appliedCommits).hasValue(1);
    }

    @Test
    void pendingOperationExcludesOtherCommandsAndDeletionPinWithoutHolding418AcrossIssueRpc() throws Exception {
        var entered=new CountDownLatch(1); var complete=Sinks.<TypeOperationOutcome>one();
        when(issues.apply(any(),any())).thenAnswer(call -> Mono.fromCallable(() -> {
            try (var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
                 var statement=connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,418))")) {
                statement.setString(1,project.toString());
                try (var result=statement.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getBoolean(1)).isTrue(); }
            }
            entered.countDown(); return true;
        }).subscribeOn(Schedulers.boundedElastic()).then(complete.asMono()));
        var first=coordinator.replace(header,project,target,actor,1,null,List.of("BUG","EPIC"),Map.of()).toFuture();
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> coordinator.replace(header(),project,target,actor,1,null,List.of("TASK"),Map.of()).block(TIMEOUT))
                    .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.ABORTED));
            assertThatThrownBy(() -> deletionPins.reserve(project,UUID.randomUUID()).block(TIMEOUT))
                    .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getDetails()).containsEntry("reason","ISSUE_TYPE_OPERATION_PENDING"));
            complete.tryEmitValue(outcome(TypeOperationState.TYPE_OPERATION_STATE_APPLIED));
            assertThat(first.get(20,TimeUnit.SECONDS).getWorkflow().getVersion()).isEqualTo(2);
        } finally { complete.tryEmitValue(outcome(TypeOperationState.TYPE_OPERATION_STATE_APPLIED)); }
    }

    @Test
    void createTakesAnAlreadyBoundTypeAndOwnedEmptyDonorCanBeReused() {
        var created=coordinator.create(header,actor,creation(List.of("EPIC"),false)).block(TIMEOUT);
        UUID createdId=UUID.fromString(created.getWorkflow().getId());
        assertThat(binding("EPIC")).isEqualTo(createdId.toString()); assertThat(version(createdId)).isEqualTo(1);
        assertThat(version(donor)).isEqualTo(2); assertThat(bindingCount(donor)).isZero();
        var reused=coordinator.replace(header(),project,donor,actor,2,null,List.of("EPIC"),Map.of()).block(TIMEOUT);
        assertThat(reused.getWorkflow().getId()).isEqualTo(donor.toString()); assertThat(version(donor)).isEqualTo(3);
        assertThat(binding("EPIC")).isEqualTo(donor.toString()); assertThat(bindingCount(createdId)).isZero();
        assertThat(version(fallback)).isEqualTo(fallbackVersion);
    }

    @Test
    void ownershipAclAndEmptyTargetRejectBeforeAnyIssueMutation() {
        assertThatThrownBy(() -> coordinator.replace(header,project,target,actor,1,null,List.of(),Map.of()).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getDetails()).containsEntry("reason","WORKFLOW_ISSUE_TYPES_EMPTY"));
        UUID foreign=UUID.randomUUID();
        databaseClient.sql("INSERT INTO taska.workflows(id,name,owner_project_id) VALUES(:id,'Foreign',:owner)")
                .bind("id",foreign).bind("owner",UUID.randomUUID()).then().block(TIMEOUT);
        assertThatThrownBy(() -> coordinator.replace(header(),project,foreign,actor,1,null,List.of("TASK"),Map.of()).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Not admin")));
        assertThatThrownBy(() -> replace(header(),1)).isInstanceOfSatisfying(DomainException.class,error ->
                assertThat(error.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED));
        verify(issues,never()).apply(any(),any()); assertThat(version(target)).isEqualTo(1);
    }

    @Test
    void emptyGraphCreationPreservesLegacyIncrementalConfigurationWhenThereAreNoIssues() {
        var created=coordinator.create(header,actor,creation(List.of("STORY"),true)).block(TIMEOUT);
        assertThat(created.getWorkflow().getStatusesCount()).isZero();
        assertThat(binding("STORY")).isEqualTo(created.getWorkflow().getId());
    }

    @Test
    void ownedEmptyIncrementalGraphCanRenameWithoutIssueRpcOrGraphUsabilityRequirements() {
        UUID empty=UUID.randomUUID();graph(empty,"Empty graph",List.of());
        var changed=mutations.mutate(ru.taska.service.WorkflowMutationService.Operation.UPDATE_WORKFLOW,
                new ru.taska.dto.WorkflowMutation("rename-empty","workflow-pg",actor,project,empty,1,
                        null,null,"Reusable empty graph",null,null,null,null,Map.of())).block(TIMEOUT);
        assertThat(changed.workflow().workflow().getName()).isEqualTo("Reusable empty graph");
        assertThat(changed.workflow().workflow().getVersion()).isEqualTo(2);
        assertThat(changed.issueTypes()).isEmpty();assertThat(changed.workflow().statuses()).isEmpty();
        assertThat(changed.workflow().transitions()).isEmpty();assertThat(bindingCount(empty)).isZero();
        verify(issues,never()).apply(any(),any());verify(issues,never()).cancel(any(),any());
        assertThat(version(fallback)).isEqualTo(fallbackVersion);
    }

    @Test
    void expandedPlanTransportLimitRejectsBeforeAnyJournalGraphOrRemoteReservationIsCommitted() {
        var codes=IntStream.range(0,400).mapToObj(i -> "TYPE_"+i).toList();
        var registry=codes.stream().map(code -> ProjectIssueTypeResponse.newBuilder().setCode(code).setName(code)
                .setGeneration(UUID.randomUUID().toString()).build()).toList();
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenReturn(Mono.just(registry));
        when(projects.getIssueTypesInternal(any())).thenReturn(Mono.just(registry));
        var manyStatuses=IntStream.range(0,100).mapToObj(i -> CreateWorkflowStatusDto.builder()
                .statusKey("S"+"X".repeat(57)+String.format("%06d",i)).name("Status "+i)
                .category(StatusCategory.TODO).sortOrder(i).build()).toList();
        var dto=WorkflowCreationDto.builder().projectId(project).name("Small command, large expansion")
                .issueTypes(codes.stream().map(IssueType::valueOf).toList()).statuses(manyStatuses)
                .transitions(List.of()).statusMappings(Map.of()).build();
        long before=databaseClient.sql("SELECT count(*) AS n FROM taska.workflows WHERE owner_project_id=:project")
                .bind("project",project).map(row -> row.get("n",Long.class)).one().block(TIMEOUT);
        assertThatThrownBy(() -> coordinator.create(header,actor,dto).block(TIMEOUT))
                .isInstanceOfSatisfying(DomainException.class,error -> {
                    assertThat(error.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(error.getDetails()).containsEntry("reason","WORKFLOW_BINDING_PLAN_TOO_LARGE");
                });
        assertThat(journal.byRequest(project,actor,header.getRequestId()).block(TIMEOUT)).isNull();
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.workflows WHERE owner_project_id=:project")
                .bind("project",project).map(row -> row.get("n",Long.class)).one().block(TIMEOUT)).isEqualTo(before);
        verify(reservations,never()).reserve(any(),any());verify(issues,never()).apply(any(),any());
        verify(issues,never()).cancel(any(),any());assertThat(gate.get().getActive()).isFalse();
    }

    private ru.taska.api.workflow.v1.ProjectWorkflow replace(Header request, int version) {
        return coordinator.replace(request,project,target,actor,version,null,List.of("BUG","EPIC"),Map.of()).block(TIMEOUT);
    }
    private Header header() { return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("workflow-pg").build(); }
    private void normalApply() { when(issues.apply(any(),any())).thenAnswer(call -> Mono.fromSupplier(() -> applied(call.getArgument(1)))); }
    private TypeOperationOutcome applied(UUID operation) {
        return receipts.computeIfAbsent(operation,key -> { appliedCommits.incrementAndGet(); return outcome(TypeOperationState.TYPE_OPERATION_STATE_APPLIED); });
    }
    private static TypeOperationOutcome outcome(TypeOperationState state) { return TypeOperationOutcome.newBuilder().setState(state).setChangedCount(1).build(); }
    private void pending(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DomainException.class,error -> {
            assertThat(error.getStatus()).isEqualTo(DomainStatus.UNAVAILABLE);
            assertThat(error.getDetails()).containsEntry("reason","WORKFLOW_BINDING_CHANGE_PENDING");
        });
    }
    private void graph(UUID id,String name,List<String> keys) {
        databaseClient.sql("INSERT INTO taska.workflows(id,name,version,owner_project_id) VALUES(:id,:name,1,:project)")
                .bind("id",id).bind("name",name).bind("project",project).then().block(TIMEOUT);
        for (int i=0;i<keys.size();i++) databaseClient.sql("INSERT INTO taska.statuses(workflow_id,status_key,name,category,sort_order) VALUES(:wf,:key,:key,:category,:sort)")
                .bind("wf",id).bind("key",keys.get(i)).bind("category",i==0?"TODO":i==keys.size()-1?"DONE":"IN_PROGRESS")
                .bind("sort",i*10).then().block(TIMEOUT);
        databaseClient.sql("INSERT INTO taska.transitions(workflow_id,from_status_id,to_status_id,name,sort_order) SELECT :wf,a.id,b.id,'Finish',0 "
                +"FROM taska.statuses a JOIN taska.statuses b ON b.workflow_id=a.workflow_id WHERE a.workflow_id=:wf AND a.status_key='TODO' AND b.status_key='DONE'")
                .bind("wf",id).then().block(TIMEOUT);
    }
    private void bind(String code,UUID workflow) {
        databaseClient.sql("INSERT INTO taska.workflow_bindings(project_id,issue_type,workflow_id,issue_type_generation) VALUES(:project,:code,:workflow,:generation)")
                .bind("project",project).bind("code",code).bind("workflow",workflow).bind("generation",generations.get(code)).then().block(TIMEOUT);
    }
    private int version(UUID workflow) { return workflows.findById(workflow).block(TIMEOUT).getVersion(); }
    private String binding(String code) {
        return databaseClient.sql("SELECT workflow_id::text AS id FROM taska.workflow_bindings WHERE project_id=:project AND issue_type=:code")
                .bind("project",project).bind("code",code).map(row -> row.get("id",String.class)).one().block(TIMEOUT);
    }
    private long bindingCount(UUID workflow) {
        return databaseClient.sql("SELECT count(*) AS n FROM taska.workflow_bindings WHERE project_id=:project AND workflow_id=:workflow")
                .bind("project",project).bind("workflow",workflow).map(row -> row.get("n",Long.class)).one().block(TIMEOUT);
    }
    private void sql(String sql) { databaseClient.sql(sql).then().block(TIMEOUT); }
    private WorkflowCreationDto creation(List<String> types, boolean empty) {
        return WorkflowCreationDto.builder().projectId(project).name("New graph")
                .issueTypes(types.stream().map(IssueType::valueOf).toList())
                .statuses(empty?List.of():List.of(CreateWorkflowStatusDto.builder().statusKey("TODO").name("Todo").category(StatusCategory.TODO).sortOrder(0).build(),
                        CreateWorkflowStatusDto.builder().statusKey("DONE").name("Done").category(StatusCategory.DONE).sortOrder(10).build()))
                .transitions(empty?List.of():List.of(CreateWorkflowTransitionDto.builder().fromStatusKey("TODO").toStatusKey("DONE").name("Finish").sortOrder(0).build()))
                .statusMappings(Map.of()).build();
    }
}
