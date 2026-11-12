import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.*;
import ru.taska.security.tenant.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only direct RPC acceptance; accepts only the owned runner's private parameter file. */
public class WorkflowAclProbe {
  private final JsonNode input;
  private final RpcContextSecurity security;
  private final WorkflowServiceGrpc.WorkflowServiceBlockingStub stub;
  private final ManagedChannel workflowChannel, authChannel;
  private final RpcControlClient auth;

  WorkflowAclProbe(Path parameters) throws Exception {
    input = JsonMapper.builder().build().readTree(Files.readAllBytes(parameters));
    Path material = Path.of(input.path("material").asText()),
        identity = material.resolve("api-gateway");
    var channels =
        new FileMtlsRpcChannels(
            identity.resolve("ca.crt"),
            identity.resolve("tls.crt"),
            identity.resolve("tls.key"),
            "test");
    authChannel = channels.create("localhost", input.path("authPort").asInt(), "auth-service");
    workflowChannel =
        channels.create("localhost", input.path("workflowPort").asInt(), "workflow-service");
    auth = new RpcControlClient(authChannel);
    var trust =
        new ManagedRpcTrust(
            material.resolve("public/trust.json"),
            identity.resolve("active.signing.json"),
            "api-gateway",
            "test",
            Clock.systemUTC());
    security =
        new RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            trust,
            (issuer, jti, until) -> auth.consume("api-gateway", issuer, jti, until),
            (context, rpc, policy) ->
                trust.allowed(context, rpc, policy)
                    ? auth.allowed(context, rpc, policy)
                    : reactor.core.publisher.Mono.just(false),
            (issuer, kid, fingerprint) -> auth.currentKey("api-gateway", issuer, kid, fingerprint));
    stub =
        WorkflowServiceGrpc.newBlockingStub(workflowChannel)
            .withDeadlineAfter(10, TimeUnit.SECONDS);
  }

  private String value(String key) {
    return input.path(key).asText();
  }

  private RequestContext context(String action) {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setContextVersion(1)
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setActorUserId(value("actor"))
                .setActorGeneration("1")
                .setMembershipGeneration("1")
                .setTenantId(value("tenant"))
                .setCredentialId(value("credential"))
                .setSessionId(value("credential"))
                .addScopes("read")
                .addScopes("write")
                .setIssuerService("api-gateway")
                .setAudienceService("workflow-service")
                .setAction(action)
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }

  private <Q extends Message, R> R call(
      String rpc,
      String action,
      Function<RequestContext, Q> build,
      BiFunction<WorkflowServiceGrpc.WorkflowServiceBlockingStub, Q, R> send) {
    var policy = RpcRoutePolicies.all().get(rpc).policy();
    var c = context(action);
    Q q = build.apply(c);
    var parent = security.authorize(c, rpc, policy).block(Duration.ofSeconds(10));
    var assertion = security.sign(parent, q, rpc, policy).block(Duration.ofSeconds(10));
    var headers = new Metadata();
    headers.put(Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
    return send.apply(stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)), q);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }

  private static void denied(Runnable operation, Status.Code code) {
    try {
      operation.run();
      throw new IllegalStateException("RPC unexpectedly admitted");
    } catch (StatusRuntimeException error) {
      require(error.getStatus().getCode() == code, "Unexpected RPC denial status");
    }
  }

  private ListProjectWorkflowsResponse list(String project, String actor) {
    return call(
        WorkflowServiceGrpc.getListProjectWorkflowsMethod().getFullMethodName(),
        "listProjectWorkflows",
        c ->
            ListProjectWorkflowsRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(
                    ListProjectWorkflowsRequestBody.newBuilder()
                        .setProjectId(project)
                        .setActorUserId(actor))
                .build(),
        (s, q) -> s.listProjectWorkflows(q));
  }

  private void scope(String project, String workflow, int version, String status) {
    call(
        WorkflowServiceGrpc.getCheckProjectWorkflowScopeMethod().getFullMethodName(),
        "checkProjectWorkflowScope",
        c ->
            CheckProjectWorkflowScopeRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setProjectId(project)
                .setActorUserId(value("actor"))
                .addIssueTypeKeys("TASK")
                .setExpectedWorkflowId(workflow)
                .setExpectedWorkflowVersion(version)
                .setStatusKey(status)
                .build(),
        (s, q) -> s.checkProjectWorkflowScope(q));
  }

  void run() {
    var all = list(value("project"), value("actor"));
    require(all.getWorkflowsCount() == 1, "Unexpected current workflow grouping");
    var graph = all.getWorkflows(0).getWorkflow();
    scope(value("project"), graph.getId(), graph.getVersion(), "TODO");
    denied(
        () -> scope(value("project"), graph.getId(), graph.getVersion() + 1, "TODO"),
        Status.Code.ABORTED);
    denied(
        () -> scope(value("project"), graph.getId(), graph.getVersion(), "UNKNOWN"),
        Status.Code.FAILED_PRECONDITION);
    denied(() -> list(value("foreignProject"), value("actor")), Status.Code.NOT_FOUND);
    denied(
        () -> list(value("project"), UUID.randomUUID().toString()), Status.Code.PERMISSION_DENIED);
    var transition = graph.getTransitions(0);
    String source =
        graph.getStatusesList().stream()
            .filter(n -> n.getId().equals(transition.getFromStatusId()))
            .findFirst()
            .orElseThrow()
            .getStatusKey();
    var result =
        call(
            WorkflowServiceGrpc.getValidateTransitionMethod().getFullMethodName(),
            "validateTransition",
            c ->
                ValidateTransitionRequest.newBuilder()
                    .setContext(c)
                    .setHeader(c.getHeader())
                    .setBody(
                        ValidateTransitionRequestBody.newBuilder()
                            .setActorUserId(value("actor"))
                            .setTransitionId(transition.getId())
                            .setIssueSnapshot(
                                IssueValidateSnapshot.newBuilder()
                                    .setProjectId(value("project"))
                                    .setIssueId(UUID.randomUUID().toString())
                                    .setIssueTypeKey("TASK")
                                    .setStatusKey(source)))
                    .build(),
            (s, q) -> s.validateTransition(q));
    require(
        result.getBody().getIsValid(), "Configuration flag disabled existing runtime transition");
    denied(
        () ->
            call(
                WorkflowServiceGrpc.getUpdateWorkflowMethod().getFullMethodName(),
                "updateWorkflow",
                c ->
                    WorkflowMutationRequest.newBuilder()
                        .setContext(c)
                        .setHeader(c.getHeader())
                        .setProjectId(value("project"))
                        .setWorkflowId(graph.getId())
                        .setActorUserId(value("actor"))
                        .setName("Flag bypass denied")
                        .build(),
                (s, q) -> s.updateWorkflow(q)),
        Status.Code.PERMISSION_DENIED);
    denied(
        () ->
            stub.listProjectWorkflows(
                ListProjectWorkflowsRequest.newBuilder()
                    .setBody(
                        ListProjectWorkflowsRequestBody.newBuilder()
                            .setProjectId(value("project"))
                            .setActorUserId(value("actor")))
                    .build()),
        Status.Code.UNAUTHENTICATED);
    require(
        list(value("project"), value("actor")).equals(all),
        "Direct denials changed workflow state");
    System.out.println(
        "{\"directWorkflowRpc\":\"PASS\",\"flagOffTransition\":\"PASS\",\"rpcChecks\":9}");
  }

  public static void main(String[] args) throws Exception {
    var probe = new WorkflowAclProbe(Path.of(args[0]));
    try {
      probe.run();
    } finally {
      probe.workflowChannel.shutdownNow();
      probe.auth.close();
    }
  }
}
