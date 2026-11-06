package ru.taska.configuration;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.*;

/** Service-only read of public business configuration through the production mTLS/JWS policy. */
public final class GrpcPlatformConfigurationSource
    implements PlatformConfigurationSnapshotCache.Source {
  private final ReactorPlatformServiceGrpc.ReactorPlatformServiceStub stub;
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String issuer;

  public GrpcPlatformConfigurationSource(
      ReactorPlatformServiceGrpc.ReactorPlatformServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer) {
    this.stub = Objects.requireNonNull(stub);
    this.security = Objects.requireNonNull(security);
    this.clock = Objects.requireNonNull(clock);
    this.issuer = Objects.requireNonNull(issuer);
  }

  @Override
  public Mono<PlatformConfigurationSnapshot> current() {
    return VerifiedTenantContext.current()
        .flatMap(
            parent -> {
              var now = clock.instant();
              var claims =
                  TrustedTenantContext.newBuilder()
                      .setContextVersion(1)
                      .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                      .setActorGeneration("service-v1")
                      .setIssuerService(issuer)
                      .setAudienceService("tenant-service")
                      .setAction("getPlatformConfigurationSnapshot")
                      .addScopes("read")
                      .setIssuedAt(now.toString())
                      .setExpiresAt(now.plusSeconds(30).toString())
                      .setAssertionId(UUID.randomUUID().toString());
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(claims)
                      .build();
              var request =
                  GetPlatformConfigurationSnapshotRequest.newBuilder().setContext(context).build();
              String rpc =
                  PlatformServiceGrpc.getGetPlatformConfigurationSnapshotMethod()
                      .getFullMethodName();
              var policy = new RpcContextSecurity.Policy(false, false);
              return security
                  .authorize(context, rpc, policy)
                  .flatMap(verified -> security.sign(verified, request, rpc, policy))
                  .flatMap(
                      assertion -> {
                        var metadata = new Metadata();
                        metadata.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                            .getPlatformConfigurationSnapshot(Mono.just(request));
                      });
            });
  }
}
