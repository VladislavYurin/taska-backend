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

/** Owned context read with production mTLS/JWS and online primary authority. */
public class ProjectContextProbe {
  private final JsonNode input;
  private final RpcContextSecurity security;
  private final ProjectServiceGrpc.ProjectServiceBlockingStub stub;
  private final ManagedChannel projectChannel, authChannel;
  private final RpcControlClient auth;

  ProjectContextProbe(Path parameters) throws Exception {
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
    projectChannel = channels.create("localhost", input.path("projectPort").asInt(), "project-service");
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
    stub = ProjectServiceGrpc.newBlockingStub(projectChannel);
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
                .setRequestId(commandKey == null ? UUID.randomUUID().toString() : commandKey)
                .setNodeId("api-gateway"))
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

  public static void main(String[] args) throws Exception {
    var probe = new ProjectContextProbe(Path.of(args[0]));
    try {
      var value = probe.call(ProjectServiceGrpc.getGetProjectContextMethod().getFullMethodName(),
          "getProjectContext", c -> GetProjectContextRequest.newBuilder().setContext(c).setHeader(c.getHeader())
              .setBody(GetProjectContextRequestBody.newBuilder().setProjectId(probe.value("project"))
                  .setActorUserId(probe.value("actor"))).build(),
          (stub, request) -> stub.getProjectContext(request));
      if (!value.getProject().getId().equals(probe.value("project")))
        throw new IllegalStateException("Wrong project context");
      // Deterministic protobuf comparison; no credentials or private profile fields leave the process.
      System.out.println("{\"contextDigest\":\"" + HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())) + "\"}");
    } finally {
      probe.projectChannel.shutdownNow();
      probe.authChannel.shutdownNow();
    }
  }
}
