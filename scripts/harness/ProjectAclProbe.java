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
import ru.taska.api.project.v1.*;
import ru.taska.security.tenant.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only direct RPC acceptance; accepts only the owned runner's private parameter file. */
public class ProjectAclProbe {
  private final JsonNode input;
  private final RpcContextSecurity security;
  private final ProjectServiceGrpc.ProjectServiceBlockingStub stub;
  private final ManagedChannel projectChannel, authChannel;
  private final RpcControlClient auth;

  ProjectAclProbe(Path parameters) throws Exception {
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
    projectChannel =
        channels.create("localhost", input.path("projectPort").asInt(), "project-service");
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
        ProjectServiceGrpc.newBlockingStub(projectChannel).withDeadlineAfter(10, TimeUnit.SECONDS);
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
                .setIssuerService("api-gateway")
                .setAudienceService("project-service")
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
      BiFunction<ProjectServiceGrpc.ProjectServiceBlockingStub, Q, R> send) {
    var policy = new RpcContextSecurity.Policy(true, false);
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

  private GetProjectInfoBatchInternalResponse batch(List<String> ids) {
    return call(
        ProjectServiceGrpc.getGetProjectInfoBatchInternalMethod().getFullMethodName(),
        "getProjectInfoBatchInternal",
        c ->
            GetProjectInfoBatchInternalRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(GetProjectInfoBatchInternalRequestBody.newBuilder().addAllProjectIds(ids))
                .build(),
        (s, q) -> s.getProjectInfoBatchInternal(q));
  }

  private ProjectKeyResponse key(String project) {
    return call(
        ProjectServiceGrpc.getGetProjectKeyInternalMethod().getFullMethodName(),
        "getProjectKeyInternal",
        c ->
            GetProjectKeyInternalRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(GetProjectKeyInternalRequestBody.newBuilder().setProjectId(project))
                .build(),
        (s, q) -> s.getProjectKeyInternal(q));
  }

  void run() {
    String own = value("project"), foreign = value("foreignProject");
    require(key(own).getProjectKey().equals("_"), "Own key missing");
    denied(() -> key(foreign), Status.Code.NOT_FOUND);
    var batch = batch(List.of(own, foreign, UUID.randomUUID().toString()));
    require(
        batch.getProjectsMap().keySet().equals(Set.of(own)),
        "Batch leaked foreign or nonexistent project");
    require(
        batch.getProjectsOrThrow(own).getIssueTypesCount() == 3,
        "Batch lost native issue type registry");
    require(batch(List.of(foreign)).getProjectsCount() == 0, "Foreign batch leaked metadata");
    var many = new ArrayList<String>();
    for (int i = 0; i < 101; i++) many.add(UUID.randomUUID().toString());
    denied(() -> batch(many), Status.Code.INVALID_ARGUMENT);
    var stats =
        call(
            ProjectServiceGrpc.getGetProjectMemberStatsBatchMethod().getFullMethodName(),
            "getProjectMemberStatsBatch",
            c ->
                GetProjectMemberStatsBatchRequest.newBuilder()
                    .setContext(c)
                    .setHeader(c.getHeader())
                    .setActorUserId(value("actor"))
                    .addAllProjectIds(List.of(own, foreign))
                    .build(),
            (s, q) -> s.getProjectMemberStatsBatch(q));
    require(
        stats.getItemsCount() == 1 && stats.getItems(0).getProjectId().equals(own),
        "Stats counted a foreign project");
    require(
        stats.getItems(0).getMemberCount() == stats.getItems(0).getMembersCount(),
        "Stats leaked stale memberships through count");
    denied(
        () ->
            call(
                ProjectServiceGrpc.getCheckProjectMemberRoleMethod().getFullMethodName(),
                "checkProjectMemberRole",
                c ->
                    CheckProjectMemberRoleRequest.newBuilder()
                        .setContext(c)
                        .setHeader(c.getHeader())
                        .setBody(
                            CheckProjectMemberRoleRequestBody.newBuilder()
                                .setProjectId(own)
                                .setUserId(UUID.randomUUID().toString()))
                        .build(),
                (s, q) -> s.checkProjectMemberRole(q)),
        Status.Code.PERMISSION_DENIED);
    denied(
        () ->
            stub.getProject(
                GetProjectRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                    .setBody(
                        GetProjectRequestBody.newBuilder()
                            .setProjectId(own)
                            .setActorUserId(value("actor")))
                    .build()),
        Status.Code.UNAUTHENTICATED);
    System.out.println(
        "{\"directRpcAcl\":\"PASS\",\"batchBeforeCounts\":\"PASS\",\"rpcChecks\":9}");
  }

  public static void main(String[] args) throws Exception {
    var probe = new ProjectAclProbe(Path.of(args[0]));
    try {
      probe.run();
    } finally {
      probe.projectChannel.shutdownNow();
      probe.auth.close();
    }
  }
}
