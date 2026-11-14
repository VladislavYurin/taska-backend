import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequestBody;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequestBody;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueByKeyRequest;
import ru.taska.api.issue.v1.GetIssueByKeyRequestBody;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.GetIssueRequestBody;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueServiceGrpc;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueRequestBody;
import ru.taska.api.issue.v1.PatchIssueResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.TransitionIssueRequestBody;
import ru.taska.security.tenant.FileMtlsRpcChannels;
import ru.taska.security.tenant.ManagedRpcTrust;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcControlClient;
import ru.taska.security.tenant.RpcRoutePolicies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приёмочные проверки прямого RPC; принимает только приватный файл параметров изолированного
 * прогона.
 */
public class IssueAclProbe {
  private final JsonNode input;
  private final RpcContextSecurity security;
  private final IssueServiceGrpc.IssueServiceBlockingStub stub;
  private final ManagedChannel issueChannel, authChannel;
  private final RpcControlClient auth;

  IssueAclProbe(Path parameters) throws Exception {
    input = JsonMapper.builder().build().readTree(Files.readAllBytes(parameters));
    Path material = Path.of(input.path("material").asString()),
        identity = material.resolve("api-gateway");
    var channels =
        new FileMtlsRpcChannels(
            identity.resolve("ca.crt"),
            identity.resolve("tls.crt"),
            identity.resolve("tls.key"),
            "test");
    authChannel = channels.create("localhost", input.path("authPort").asInt(), "auth-service");
    issueChannel = channels.create("localhost", input.path("issuePort").asInt(), "issue-service");
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
    stub = IssueServiceGrpc.newBlockingStub(issueChannel);
  }

  private String value(String key) {
    return input.path(key).asString("");
  }

  private String commandKey;

  private RequestContext context(String action) {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(
            Header.newBuilder()
                .setRequestId(commandKey == null ? UUID.randomUUID().toString() : commandKey))
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
                .setAudienceService("issue-service")
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
      BiFunction<IssueServiceGrpc.IssueServiceBlockingStub, Q, R> send) {
    var policy = RpcRoutePolicies.all().get(rpc).policy();
    var c = context(action);
    Q q = build.apply(c);
    var parent = security.authorize(c, rpc, policy).block(Duration.ofSeconds(10));
    var assertion = security.sign(parent, q, rpc, policy).block(Duration.ofSeconds(10));
    var headers = new Metadata();
    headers.put(Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
    return send.apply(
        stub.withDeadlineAfter(20, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)),
        q);
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

  private IssueResponse create(String project, String actor, String key) {
    return call(
        IssueServiceGrpc.getCreateIssueMethod().getFullMethodName(),
        "createIssue",
        c ->
            CreateIssueRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(
                    CreateIssueRequestBody.newBuilder()
                        .setProjectId(project)
                        .setReporterId(actor)
                        .setIdempotencyKey(key)
                        .setIssueTypeKey("TASK")
                        .setSummary("Owned native issue")
                        .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM))
                .build(),
        (s, q) -> s.createIssue(q));
  }

  private IssueWithHistoryResponse get(String id) {
    return call(
        IssueServiceGrpc.getGetIssueMethod().getFullMethodName(),
        "getIssue",
        c ->
            GetIssueRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(
                    GetIssueRequestBody.newBuilder().setIssueId(id).setActorUserId(value("actor")))
                .build(),
        (s, q) -> s.getIssue(q));
  }

  private IssueResponse byKey(String key) {
    return call(
        IssueServiceGrpc.getGetIssueByKeyMethod().getFullMethodName(),
        "getIssueByKey",
        c ->
            GetIssueByKeyRequest.newBuilder()
                .setContext(c)
                .setHeader(c.getHeader())
                .setBody(
                    GetIssueByKeyRequestBody.newBuilder()
                        .setIssueKey(key)
                        .setActorUserId(value("actor")))
                .build(),
        (s, q) -> s.getIssueByKey(q));
  }

  private PatchIssueResponse patch(String id, int version, String key) {
    commandKey = key;
    try {
      return call(
          IssueServiceGrpc.getPatchIssueMethod().getFullMethodName(),
          "patchIssue",
          c ->
              PatchIssueRequest.newBuilder()
                  .setContext(c)
                  .setHeader(c.getHeader())
                  .setBody(
                      PatchIssueRequestBody.newBuilder()
                          .setIssueId(id)
                          .setActorUserId(value("actor"))
                          .setVersion(version)
                          .setSummary("Changed native issue"))
                  .build(),
          (s, q) -> s.patchIssue(q));
    } finally {
      commandKey = null;
    }
  }

  private IssueWithHistoryResponse transition(String id, int version, String key) {
    commandKey = key;
    try {
      return call(
          IssueServiceGrpc.getTransitionIssueMethod().getFullMethodName(),
          "transitionIssue",
          c ->
              TransitionIssueRequest.newBuilder()
                  .setContext(c)
                  .setHeader(c.getHeader())
                  .setBody(
                      TransitionIssueRequestBody.newBuilder()
                          .setIssueId(id)
                          .setActorUserId(value("actor"))
                          .setExpectedVersion(version)
                          .setTransitionId(value("transition")))
                  .build(),
          (s, q) -> s.transitionIssue(q));
    } finally {
      commandKey = null;
    }
  }

  private DeleteIssueResponse delete(String id, String key) {
    commandKey = key;
    try {
      return call(
          IssueServiceGrpc.getDeleteIssueMethod().getFullMethodName(),
          "deleteIssue",
          c ->
              DeleteIssueRequest.newBuilder()
                  .setContext(c)
                  .setHeader(c.getHeader())
                  .setBody(
                      DeleteIssueRequestBody.newBuilder()
                          .setIssueId(id)
                          .setActorUserId(value("actor")))
                  .build(),
          (s, q) -> s.deleteIssue(q));
    } finally {
      commandKey = null;
    }
  }

  void run() {
    String mode = value("mode");
    if (mode.equals("denyWrite")) {
      var current = get(value("issue"));
      denied(
          () ->
              patch(value("issue"), current.getIssue().getVersion(), UUID.randomUUID().toString()),
          Status.Code.PERMISSION_DENIED);
      require(get(value("issue")).equals(current), "Denied write changed state/history");
      System.out.println("{\"aclReadAndWriteDenial\":\"PASS\"}");
      return;
    }
    if (mode.equals("adminWrite")) {
      var current = get(value("issue"));
      var result =
          patch(value("issue"), current.getIssue().getVersion(), UUID.randomUUID().toString());
      require(
          result.getIssue().getVersion() == current.getIssue().getVersion() + 1,
          "Administrative write did not advance version");
      System.out.println("{\"administrativeIssueWrite\":\"PASS\"}");
      return;
    }
    if (mode.equals("delete")) {
      String key = UUID.randomUUID().toString();
      var result = delete(value("issue"), key);
      require(delete(value("issue"), key).equals(result), "Delete retry changed response");
      denied(() -> get(value("issue")), Status.Code.NOT_FOUND);
      System.out.println("{\"deleteReplay\":\"PASS\"}");
      return;
    }
    String key = UUID.randomUUID().toString();
    var first = create(value("project"), value("actor"), key);
    require(first.getVersion() == 1, "Create version is not one");
    require(
        create(value("project"), value("actor"), key).equals(first),
        "Create retry changed response");
    denied(
        () -> create(value("foreignProject"), value("actor"), UUID.randomUUID().toString()),
        Status.Code.NOT_FOUND);
    denied(
        () -> create(value("project"), UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        Status.Code.PERMISSION_DENIED);
    denied(() -> get(value("foreignIssue")), Status.Code.NOT_FOUND);
    require(
        byKey(first.getIssueKey()).equals(first), "GetByKey did not resolve the selected tenant");
    String patchKey = UUID.randomUUID().toString();
    var changed = patch(first.getId(), 1, patchKey);
    require(changed.getIssue().getVersion() == 2, "Patch version did not advance once");
    require(patch(first.getId(), 1, patchKey).equals(changed), "Patch replay changed response");
    denied(() -> patch(first.getId(), 1, UUID.randomUUID().toString()), Status.Code.ABORTED);
    require(
        create(value("project"), value("actor"), key).equals(first),
        "Create replay changed after mutation");
    String transitionKey = UUID.randomUUID().toString();
    var moved = transition(first.getId(), 2, transitionKey);
    require(moved.getIssue().getVersion() == 3, "Transition version did not advance");
    require(
        transition(first.getId(), 2, transitionKey).equals(moved),
        "Transition retry revalidated its changed source");
    require(get(first.getId()).getHistoryCount() == 3, "Mutation history was lost or duplicated");
    denied(
        () ->
            stub.withDeadlineAfter(20, TimeUnit.SECONDS)
                .getIssue(
                    GetIssueRequest.newBuilder()
                        .setBody(
                            GetIssueRequestBody.newBuilder()
                                .setIssueId(first.getId())
                                .setActorUserId(value("actor")))
                        .build()),
        Status.Code.UNAUTHENTICATED);
    System.out.println(
        "{\"nativeIssueRpc\":\"PASS\",\"rpcChecks\":13,\"issue\":\"" + first.getId() + "\"}");
  }

  public static void main(String[] args) throws Exception {
    var probe = new IssueAclProbe(Path.of(args[0]));
    try {
      probe.run();
    } finally {
      probe.issueChannel.shutdownNow();
      probe.auth.close();
    }
  }
}
