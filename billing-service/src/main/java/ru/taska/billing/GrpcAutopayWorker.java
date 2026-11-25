package ru.taska.billing;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AutopayDispatchResult;
import ru.taska.api.b2b.v1.AutopayDispatchRpcRequest;
import ru.taska.api.b2b.v1.AutopayWorkerServiceGrpc;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ReactorAutopayWorkerServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.RpcContextSecurity;

/** Сервисный контекст создаётся заново и проверяется получателем на реальном mTLS-канале. */
public final class GrpcAutopayWorker {
  private final ReactorAutopayWorkerServiceGrpc.ReactorAutopayWorkerServiceStub stub;
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String generation;

  public GrpcAutopayWorker(
      ReactorAutopayWorkerServiceGrpc.ReactorAutopayWorkerServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String generation) {
    if (generation == null || generation.isBlank() || generation.length() > 100)
      throw new IllegalArgumentException("Generation required");
    this.stub = stub;
    this.security = security;
    this.clock = clock;
    this.generation = generation;
  }

  public Mono<AutopayDispatchResult> dispatch(UUID tenant, UUID invoice) {
    return Mono.defer(
        () -> {
          var now = clock.instant();
          String rpc = AutopayWorkerServiceGrpc.getDispatchAutopayMethod().getFullMethodName();
          var policy = new RpcContextSecurity.Policy(true, true);
          var context =
              RequestContext.newBuilder()
                  .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                  .setContext(
                      TrustedTenantContext.newBuilder()
                          .setContextVersion(1)
                          .setTenantId(tenant.toString())
                          .setActorGeneration(generation)
                          .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                          .setServicePurpose("billing")
                          .setServiceIdentity("billing-service")
                          .setIssuerService("billing-service")
                          .setAudienceService("billing-service")
                          .setAction("dispatchAutopay")
                          .addScopes("write")
                          .addScopes("payment:recurring")
                          .setIssuedAt(now.toString())
                          .setExpiresAt(now.plusSeconds(30).toString())
                          .setAssertionId(UUID.randomUUID().toString()))
                  .build();
          var request =
              AutopayDispatchRpcRequest.newBuilder()
                  .setContext(context)
                  .setTenantId(tenant.toString())
                  .setInvoiceId(invoice.toString())
                  .build();
          return security
              .authorize(context, rpc, policy)
              .flatMap(parent -> security.sign(parent, request, rpc, policy))
              .flatMap(
                  assertion -> {
                    var metadata = new Metadata();
                    metadata.put(
                        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                        assertion);
                    return stub.withInterceptors(
                            MetadataUtils.newAttachHeadersInterceptor(metadata))
                        .withDeadlineAfter(25, TimeUnit.SECONDS)
                        .dispatchAutopay(request);
                  });
        });
  }
}
