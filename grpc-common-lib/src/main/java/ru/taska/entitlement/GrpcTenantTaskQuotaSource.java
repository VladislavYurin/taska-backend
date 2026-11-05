package ru.taska.entitlement;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/**
 * Supply a production mTLS stub and online service authority; no PSP calls or fallback identity.
 */
public final class GrpcTenantTaskQuotaSource implements TenantTaskQuotaSource {
  private final ReactorProjectQuotaReadServiceGrpc.ReactorProjectQuotaReadServiceStub stub;
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String issuer;

  public GrpcTenantTaskQuotaSource(
      ReactorProjectQuotaReadServiceGrpc.ReactorProjectQuotaReadServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer) {
    this.stub = Objects.requireNonNull(stub);
    this.security = Objects.requireNonNull(security);
    this.clock = Objects.requireNonNull(clock);
    this.issuer = Objects.requireNonNull(issuer);
  }

  @Override
  public Mono<TenantTaskQuotaSummary> current(UUID tenant) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent -> {
              if (!tenant.toString().equals(parent.tenantId()))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "Tenant mismatch"));
              var now = clock.instant();
              var claims =
                  TrustedTenantContext.newBuilder()
                      .setContextVersion(1)
                      .setTenantId(tenant.toString())
                      .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                      .setActorGeneration("service-v1")
                      .setIssuerService(issuer)
                      .setAudienceService("issue-service")
                      .setAction("getTenantTaskQuotaSummary")
                      .addScopes("read")
                      .setIssuedAt(now.toString())
                      .setExpiresAt(now.plusSeconds(30).toString())
                      .setAssertionId(UUID.randomUUID().toString());
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(claims)
                      .build();
              var builder =
                  GetTenantTaskQuotaSummaryRequest.newBuilder()
                      .setContext(context)
                      .setTenantId(tenant.toString());
              var request = builder.build();
              String rpc =
                  ProjectQuotaReadServiceGrpc.getGetTenantTaskQuotaSummaryMethod()
                      .getFullMethodName();
              var policy = new RpcContextSecurity.Policy(true, false);
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
                            .getTenantTaskQuotaSummary(Mono.just(request));
                      });
            });
  }
}
