import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import ru.taska.api.b2b.v1.IssueRecipientServiceGrpc;
import ru.taska.api.b2b.v1.IssueRecipientsRequest;
import ru.taska.api.b2b.v1.IssueRecipientsResponse;
import ru.taska.security.tenant.FileMtlsRpcChannels;
import ru.taska.security.tenant.ManagedRpcTrust;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcControlClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Реальные mTLS/JWS и purpose; только приватные параметры своего harness, без вывода данных. */
public final class IssueRecipientsProbe implements AutoCloseable {
  private final JsonNode input;
  private final ManagedChannel issueChannel, authChannel;
  private final RpcControlClient auth;
  private final RpcContextSecurity security;
  private final ManagedServiceContexts contexts;
  private final IssueRecipientServiceGrpc.IssueRecipientServiceBlockingStub stub;
  private final String rpc = IssueRecipientServiceGrpc.getResolveMethod().getFullMethodName();
  private final RpcContextSecurity.Policy policy = new RpcContextSecurity.Policy(true, false);

  IssueRecipientsProbe(Path path) throws Exception {
    input = JsonMapper.builder().build().readTree(Files.readAllBytes(path));
    Path material = Path.of(input.path("material").asString()),
        identity = material.resolve("notification-service");
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
            "notification-service",
            "test",
            Clock.systemUTC());
    security =
        new RpcContextSecurity(
            "notification-service",
            Clock.systemUTC(),
            trust,
            (issuer, jti, until) -> auth.consume("notification-service", issuer, jti, until),
            (context, method, p) ->
                trust.allowed(context, method, p)
                    ? auth.allowed(context, method, p)
                    : reactor.core.publisher.Mono.just(false),
            (issuer, kid, fingerprint) ->
                auth.currentKey("notification-service", issuer, kid, fingerprint));
    contexts = new ManagedServiceContexts("notification-service", trust, security);
    stub = IssueRecipientServiceGrpc.newBlockingStub(issueChannel);
  }

  private IssueRecipientsResponse resolve(String tenant, String issue, String purpose) {
    var c =
        contexts
            .authorize(
                purpose,
                "issue-service",
                rpc,
                "resolveIssueRecipients",
                UUID.fromString(tenant),
                Set.of("read", "notifications:recipients"),
                policy)
            .block(Duration.ofSeconds(10));
    var q =
        IssueRecipientsRequest.newBuilder()
            .setContext(RpcContextBridge.details(c))
            .setIssueId(issue)
            .setLimit(100)
            .build();
    var assertion = security.sign(c, q, rpc, policy).block(Duration.ofSeconds(10));
    var headers = new Metadata();
    headers.put(Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
    return stub.withDeadlineAfter(20, TimeUnit.SECONDS)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        .resolve(q);
  }

  private void run() {
    String tenant = input.path("tenant").asString(), issue = input.path("issue").asString();
    var expected = new HashSet<String>();
    for (var id : input.path("expected")) expected.add(id.asString());
    var result = resolve(tenant, issue, "NOTIFICATION_DELIVERY");
    if (!new HashSet<>(result.getUserIdsList()).equals(expected))
      throw new IllegalStateException("Current recipients differ from expected eligible set");
    denied(() -> resolve(tenant, issue, "STORAGE_CLEANUP"), Status.Code.PERMISSION_DENIED);
    denied(
        () -> resolve(input.path("foreignTenant").asString(), issue, "NOTIFICATION_DELIVERY"),
        Status.Code.NOT_FOUND);
    System.out.println(
        "{\"currentRecipientPrimary\":\"PASS\",\"recipientPurposeGuard\":\"PASS\",\"recipientTenantIsolation\":\"PASS\"}");
  }

  private static void denied(Runnable call, Status.Code expected) {
    try {
      call.run();
      throw new IllegalStateException("Unexpected recipient admission");
    } catch (StatusRuntimeException error) {
      if (error.getStatus().getCode() != expected)
        throw new IllegalStateException("Unexpected recipient denial status");
    }
  }

  public void close() {
    issueChannel.shutdownNow();
    auth.close();
  }

  public static void main(String[] args) throws Exception {
    try (var probe = new IssueRecipientsProbe(Path.of(args[0]))) {
      probe.run();
    }
  }
}
