package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.registry.v1.ProjectTypeOperationGate;
import ru.taska.api.workflow.v1.*;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.grpc.GrpcWorkflowServiceAdapter;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.WorkflowProjectOperationClient;

/** PostgreSQL resolution and real TCP: only the requested scope is validated, no graph is returned. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowScopeCheckIT extends AbstractIT {
    private static final Duration TIMEOUT=Duration.ofSeconds(8);
    private static final UUID DEFAULT=UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Autowired GrpcWorkflowServiceAdapter adapter;
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean WorkflowProjectOperationClient operations;
    private final AtomicReference<List<ProjectIssueTypeResponse>> registry=new AtomicReference<>();
    private UUID project,actor,first,second;
    private Server server;
    private ManagedChannel channel;
    private ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub client;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) {
        properties.add("spring.grpc.server.address",() -> "127.0.0.1:0");
        properties.add("app.workflow-binding-changes.recovery-interval",() -> "1h");
    }
    @BeforeEach
    void prepare() throws Exception {
        project=UUID.randomUUID();actor=UUID.randomUUID();first=UUID.randomUUID();second=UUID.randomUUID();
        registry.set(ru.taska.RegistryFixtures.types(project,IssueType.TASK,IssueType.BUG,IssueType.STORY));
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenAnswer(call -> Mono.defer(() -> Mono.just(registry.get())));
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.getDefaultInstance()));
        graph(first,7);graph(second,3);bind("TASK",first);bind("BUG",second);
        status(first,"TODO");status(second,"OTHER");
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(adapter).build().start();
        channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        client=ReactorWorkflowServiceGrpc.newReactorStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
    }
    @AfterEach
    void close() throws Exception {
        if (channel!=null) channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if (server!=null) server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
    }

    @Test
    void statusCheckSupportsNoTodoHiddenOnlyAndEmptyGraphsWithoutPublicGetConstraints() {
        assertThat(check(scope("TASK").setStatusKey("TODO")).getSerializedSize()).isZero();
        assertThat(check(scope("BUG").setStatusKey("OTHER")).getSerializedSize()).isZero();
        rejected(scope("BUG").setStatusKey("TODO"),Status.Code.FAILED_PRECONDITION,"STATUS_NOT_IN_WORKFLOW");
        databaseClient.sql("DELETE FROM taska.statuses WHERE workflow_id=:workflow").bind("workflow",second).then().block(TIMEOUT);
        assertThat(check(scope("BUG").setExpectedWorkflowId(second.toString()).setExpectedWorkflowVersion(3)).getSerializedSize()).isZero();
        rejected(scope("BUG").setStatusKey("OTHER"),Status.Code.FAILED_PRECONDITION,"STATUS_NOT_IN_WORKFLOW");
        assertThat(check(scope("STORY").setStatusKey("TODO")).getSerializedSize()).isZero();
    }

    @Test
    void eachRequestedTypeMustMatchTheActualWorkflowAndVersion() {
        check(scope("TASK").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7));
        rejected(scope("TASK").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(6),Status.Code.ABORTED,"WORKFLOW_CHANGED");
        rejected(scope("TASK").addIssueTypeKeys("BUG").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7),
                Status.Code.ABORTED,"WORKFLOW_CHANGED");
        databaseClient.sql("UPDATE taska.workflow_bindings SET workflow_id=:target WHERE project_id=:project AND issue_type='BUG'")
                .bind("target",first).bind("project",project).then().block(TIMEOUT);
        check(scope("TASK").addIssueTypeKeys("BUG").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7));
        rejected(scope("ALIEN"),Status.Code.INVALID_ARGUMENT,"ISSUE_TYPE_NOT_IN_PROJECT");
    }

    @Test
    void recreatedAndLegacyNullGenerationsCannotResurrectAnOlderBinding() {
        databaseClient.sql("UPDATE taska.workflow_bindings SET issue_type_generation=NULL WHERE project_id=:project AND issue_type='TASK'")
                .bind("project",project).then().block(TIMEOUT);
        check(scope("TASK").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7));
        registry.set(registry.get().stream().map(type -> type.getCode().equals("TASK")?
                type.toBuilder().setGeneration(UUID.randomUUID().toString()).build():type).toList());
        rejected(scope("TASK").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7),Status.Code.ABORTED,"WORKFLOW_CHANGED");
        check(scope("TASK").setExpectedWorkflowId(DEFAULT.toString()).setExpectedWorkflowVersion(1).setStatusKey("TODO"));
        databaseClient.sql("UPDATE taska.workflows SET is_active=false WHERE id=:workflow").bind("workflow",second).then().block(TIMEOUT);
        check(scope("BUG").setExpectedWorkflowId(DEFAULT.toString()).setExpectedWorkflowVersion(1).setStatusKey("TODO"));
    }

    @Test
    void registryReadAclPrecedesPendingStateAndGenericGateDoesNotLeakOperationIdentity() {
        UUID privateOperation=UUID.randomUUID();
        when(operations.gate(any())).thenReturn(Mono.just(ProjectTypeOperationGate.newBuilder().setActive(true)
                .setOperationId(privateOperation.toString()).setKind("WORKFLOW_BINDINGS").build()));
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Access denied")));
        clearInvocations(operations);
        rejected(scope("TASK"),Status.Code.PERMISSION_DENIED,null);verify(operations,never()).gate(any());
        when(projects.listIssueTypeDetails(any(),any(),any(),any())).thenReturn(Mono.just(registry.get()));
        assertThatThrownBy(() -> check(scope("TASK"))).isInstanceOfSatisfying(StatusRuntimeException.class,error -> {
            assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
            assertThat(mapper.GrpcErrorDetails.decode(error.getTrailers())).containsEntry("reason","ISSUE_TYPE_OPERATION_PENDING")
                    .doesNotContainKey("operationId");
            assertThat(error.getMessage()).doesNotContain(privateOperation.toString());
        });
    }

    @Test
    void scopeReadDoesNotAcquireWorkflow418WhileAnIssueCallerMayHold433() throws Exception {
        try (var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,418))")) {
                statement.setString(1,project.toString());statement.execute();
                check(scope("TASK").setExpectedWorkflowId(first.toString()).setExpectedWorkflowVersion(7).setStatusKey("TODO"));
            } finally { connection.rollback(); }
        }
    }

    @Test
    void multipleLargeUnrelatedGraphsCannotBreakConstantSizeStatusAndVersionChecks() {
        databaseClient.sql("UPDATE taska.statuses SET name=repeat('N',2300000) WHERE workflow_id IN (:first,:second)")
                .bind("first",first).bind("second",second).then().block(TIMEOUT);
        var list=ListProjectWorkflowsRequest.newBuilder().setHeader(header())
                .setBody(ListProjectWorkflowsRequestBody.newBuilder().setProjectId(project.toString()).setActorUserId(actor.toString())).build();
        assertThatThrownBy(() -> client.listProjectWorkflows(list).block(TIMEOUT)).isInstanceOfSatisfying(StatusRuntimeException.class,
                error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
        clearInvocations(projects);
        assertThat(check(scope("TASK").setStatusKey("TODO")).getSerializedSize()).isZero();
        verify(projects).listIssueTypeDetails(any(),any(),any(),any());
        assertThat(check(scope("BUG").setExpectedWorkflowId(second.toString()).setExpectedWorkflowVersion(3)).getSerializedSize()).isZero();
        assertThat(check(scope("STORY").setStatusKey("TODO")).getSerializedSize()).isZero();
    }

    @Test
    void malformedScopeAndPartialVersionPreconditionsFailWithBoundedErrors() {
        rejected(scope().setStatusKey("TODO"),Status.Code.INVALID_ARGUMENT,null);
        rejected(scope("bad-key"),Status.Code.INVALID_ARGUMENT,null);
        rejected(scope("TASK").setExpectedWorkflowVersion(7),Status.Code.INVALID_ARGUMENT,null);
        rejected(scope("TASK").setExpectedWorkflowId("not-a-uuid").setExpectedWorkflowVersion(7),Status.Code.INVALID_ARGUMENT,null);
        rejected(scope("TASK").setStatusKey("invalid-status"),Status.Code.INVALID_ARGUMENT,null);
    }

    private com.google.protobuf.Empty check(CheckProjectWorkflowScopeRequest.Builder request) {
        return client.checkProjectWorkflowScope(request.build()).block(TIMEOUT);
    }
    private void rejected(CheckProjectWorkflowScopeRequest.Builder request,Status.Code code,String reason) {
        assertThatThrownBy(() -> check(request)).isInstanceOfSatisfying(StatusRuntimeException.class,error -> {
            assertThat(error.getStatus().getCode()).isEqualTo(code);
            if (reason!=null) assertThat(mapper.GrpcErrorDetails.decode(error.getTrailers())).containsEntry("reason",reason);
        });
    }
    private Header header() { return Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("scope-check-it").build(); }
    private CheckProjectWorkflowScopeRequest.Builder scope(String...codes) {
        return CheckProjectWorkflowScopeRequest.newBuilder().setHeader(header()).setProjectId(project.toString())
                .setActorUserId(actor.toString()).addAllIssueTypeKeys(List.of(codes));
    }
    private void graph(UUID workflow,int version) {
        databaseClient.sql("INSERT INTO taska.workflows(id,name,version,owner_project_id) VALUES(:id,'Small metadata',:version,:project)")
                .bind("id",workflow).bind("version",version).bind("project",project).then().block(TIMEOUT);
    }
    private void bind(String code,UUID workflow) {
        UUID generation=UUID.fromString(registry.get().stream().filter(type -> type.getCode().equals(code)).findFirst().orElseThrow().getGeneration());
        databaseClient.sql("INSERT INTO taska.workflow_bindings(project_id,issue_type,workflow_id,issue_type_generation) VALUES(:project,:type,:workflow,:generation)")
                .bind("project",project).bind("type",code).bind("workflow",workflow).bind("generation",generation).then().block(TIMEOUT);
    }
    private void status(UUID workflow,String key) {
        databaseClient.sql("INSERT INTO taska.statuses(workflow_id,status_key,name,category,sort_order) VALUES(:workflow,:key,'Small status','TODO',0)")
                .bind("workflow",workflow).bind("key",key).then().block(TIMEOUT);
    }
}
