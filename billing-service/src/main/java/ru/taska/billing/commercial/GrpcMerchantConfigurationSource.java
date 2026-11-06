package ru.taska.billing.commercial;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.MerchantConfigurationServiceGrpc;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.ReactorMerchantConfigurationServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.ResolveMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Stub must be supplied with mTLS; the recipient additionally verifies service/action authority.
 */
public final class GrpcMerchantConfigurationSource
    implements CommercialDependencies.ConfigurationSource {
  private final ReactorMerchantConfigurationServiceGrpc.ReactorMerchantConfigurationServiceStub
      stub;
  private final RpcContextSecurity security;
  private final Clock clock;

  public GrpcMerchantConfigurationSource(
      ReactorMerchantConfigurationServiceGrpc.ReactorMerchantConfigurationServiceStub stub,
      RpcContextSecurity security,
      Clock clock) {
    this.stub = stub;
    this.security = security;
    this.clock = clock;
  }

  @Override
  public Mono<MerchantConfigurationSnapshot> current() {
    return resolve(null);
  }

  @Override
  public Mono<MerchantConfigurationSnapshot> version(String version) {
    return resolve(version);
  }

  private Mono<MerchantConfigurationSnapshot> resolve(String version) {
    return Mono.defer(
        () -> {
          var now = clock.instant();
          var context =
              RequestContext.newBuilder()
                  .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                  .setContext(
                      TrustedTenantContext.newBuilder()
                          .setContextVersion(1)
                          .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                          .setActorGeneration("service-v1")
                          .setIssuerService("billing-service")
                          .setAudienceService("tenant-service")
                          .setAction("resolveMerchantConfiguration")
                          .addScopes("read")
                          .addScopes("commercial:resolve")
                          .setIssuedAt(now.toString())
                          .setExpiresAt(now.plusSeconds(30).toString())
                          .setAssertionId(UUID.randomUUID().toString()))
                  .build();
          var request = ResolveMerchantConfigurationRequest.newBuilder().setContext(context);
          if (version != null) request.setVersion(version);
          var message = request.build();
          String rpc =
              MerchantConfigurationServiceGrpc.getResolveMerchantConfigurationMethod()
                  .getFullMethodName();
          var policy = new RpcContextSecurity.Policy(false, false);
          return security
              .authorize(context, rpc, policy)
              .flatMap(parent -> security.sign(parent, message, rpc, policy))
              .flatMap(
                  assertion -> {
                    var metadata = new Metadata();
                    metadata.put(
                        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                        assertion);
                    return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                        .resolveMerchantConfiguration(Mono.just(message));
                  });
        });
  }
}
