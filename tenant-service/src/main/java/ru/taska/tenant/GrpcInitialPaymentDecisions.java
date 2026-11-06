package ru.taska.tenant;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.RpcContextSecurity;

/** Root's own primary read, before opening its transaction; callback scopes are not delegated. */
public final class GrpcInitialPaymentDecisions implements TenantCreationDecisions.Authority {
  private final ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub stub;
  private final RpcContextSecurity security;
  private final Clock clock;

  public GrpcInitialPaymentDecisions(
      ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub stub,
      RpcContextSecurity security,
      Clock clock) {
    this.stub = stub;
    this.security = security;
    this.clock = clock;
  }

  public Mono<TenantCreationDecisions.InitialPayment> resolve(UUID tenant, UUID decision) {
    return Mono.defer(
            () -> {
              var now = clock.instant();
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(
                          TrustedTenantContext.newBuilder()
                              .setContextVersion(1)
                              .setTenantId(tenant.toString())
                              .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                              .setActorGeneration("service-v1")
                              .setIssuerService("tenant-service")
                              .setAudienceService("billing-service")
                              .setAction("getTenantInitialPaymentDecision")
                              .addScopes("read")
                              .setIssuedAt(now.toString())
                              .setExpiresAt(now.plusSeconds(30).toString())
                              .setAssertionId(UUID.randomUUID().toString()))
                      .build();
              var request =
                  GetInitialPaymentDecisionRequest.newBuilder()
                      .setContext(context)
                      .setTenantId(tenant.toString())
                      .setDecisionId(decision.toString())
                      .build();
              var rpc =
                  PaymentSettlementServiceGrpc.getGetInitialPaymentDecisionMethod()
                      .getFullMethodName();
              var policy = new RpcContextSecurity.Policy(true, false);
              return security
                  .authorize(context, rpc, policy)
                  .flatMap(v -> security.sign(v, request, rpc, policy))
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return stub.withInterceptors(
                                MetadataUtils.newAttachHeadersInterceptor(headers))
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .getInitialPaymentDecision(request);
                      });
            })
        .map(
            d ->
                new TenantCreationDecisions.InitialPayment(
                    UUID.fromString(d.getDecisionId()),
                    UUID.fromString(d.getTenantId()),
                    UUID.fromString(d.getCreationOperationId()),
                    UUID.fromString(d.getFinancialFactId()),
                    UUID.fromString(d.getInvoiceId()),
                    UUID.fromString(d.getPlanVersionId()),
                    true,
                    UUID.fromString(d.getCoverageId()),
                    TenantCreationDecisions.Precision.EXACT,
                    Instant.parse(d.getOccurredAt()),
                    null,
                    null,
                    Instant.parse(d.getInvoiceDeadline())));
  }
}
