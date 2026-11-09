package ru.taska.billing.ingress;

import com.google.protobuf.Message;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Service-origin calls are authorized, signed and verified over the actual configured mTLS channel.
 */
public final class GrpcProviderInboxWorker implements ProviderInbox.SettlementDelivery {
  private final ReactorProviderInboxServiceGrpc.ReactorProviderInboxServiceStub inbox;
  private final ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub settlement;
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String generation;

  public GrpcProviderInboxWorker(
      ReactorProviderInboxServiceGrpc.ReactorProviderInboxServiceStub inbox,
      ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub settlement,
      RpcContextSecurity security,
      Clock clock,
      String generation) {
    ProviderWebhookRegistry.text(generation, 100);
    this.inbox = inbox;
    this.settlement = settlement;
    this.security = security;
    this.clock = clock;
    this.generation = generation;
  }

  public Mono<Void> process(ProviderInboxStore.Pending item) {
    return call(
            item.tenantId(),
            ProviderInboxServiceGrpc.getProcessProviderWebhookMethod().getFullMethodName(),
            "processProviderWebhook",
            "inbox:process",
            c ->
                ProcessProviderWebhookRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(item.tenantId().toString())
                    .setReceiptId(item.receiptId().toString())
                    .build(),
            (r, h) ->
                inbox
                    .withInterceptors(h)
                    .withDeadlineAfter(20, TimeUnit.SECONDS)
                    .processProviderWebhook(r))
        .then();
  }

  /** Persist the trusted route after the PSP identity is durable, including after lost ACK. */
  public Mono<Void> register(ru.taska.billing.payment.PaymentAttemptStore.Attempt attempt) {
    if (attempt == null
        || attempt.providerPaymentId() == null
        || attempt.providerPaymentId().isBlank())
      return Mono.error(
          new ru.taska.billing.payment.ProviderException(
              ru.taska.billing.payment.ProviderException.Reason.INVALID_STATE));
    return call(
            attempt.tenantId(),
            ProviderInboxServiceGrpc.getRegisterProviderPaymentMethod().getFullMethodName(),
            "registerProviderPayment",
            "inbox:map",
            c ->
                RegisterProviderPaymentRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(attempt.tenantId().toString())
                    .setInvoiceId(attempt.invoiceId().toString())
                    .setProvider(attempt.provider())
                    .setProviderAccountId(attempt.merchantAccount())
                    .setEnvironment(
                        attempt.environment()
                                == ru.taska.billing.payment.PaymentProvider.Environment.TEST
                            ? ProviderEnvironment.PROVIDER_ENVIRONMENT_TEST
                            : ProviderEnvironment.PROVIDER_ENVIRONMENT_LIVE)
                    .setProviderPaymentId(attempt.providerPaymentId())
                    .build(),
            (r, h) ->
                inbox
                    .withInterceptors(h)
                    .withDeadlineAfter(20, TimeUnit.SECONDS)
                    .registerProviderPayment(r))
        .then();
  }

  @Override
  public Mono<Void> apply(UUID tenant, UUID evidence) {
    return call(
            tenant,
            PaymentSettlementServiceGrpc.getApplyVerifiedPaymentMethod().getFullMethodName(),
            "applyVerifiedPayment",
            "ledger:write",
            c ->
                ApplyVerifiedPaymentRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(tenant.toString())
                    .setEvidenceId(evidence.toString())
                    .build(),
            (r, h) ->
                settlement
                    .withInterceptors(h)
                    .withDeadlineAfter(15, TimeUnit.SECONDS)
                    .applyVerifiedPayment(r))
        .then();
  }

  private <Q extends Message, R> Mono<R> call(
      UUID tenant,
      String rpc,
      String action,
      String scope,
      Function<RequestContext, Q> request,
      BiFunction<Q, ClientInterceptor, Mono<R>> send) {
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
                          .setActorGeneration(generation)
                          .setTenantId(tenant.toString())
                          .setIssuerService("billing-service")
                          .setAudienceService("billing-service")
                          .setAction(action)
                          .addScopes("write")
                          .addScopes(scope)
                          .setIssuedAt(now.toString())
                          .setExpiresAt(now.plusSeconds(30).toString())
                          .setAssertionId(UUID.randomUUID().toString()))
                  .build();
          Q body = request.apply(context);
          var policy = new RpcContextSecurity.Policy(true, true);
          return security
              .authorize(context, rpc, policy)
              .flatMap(v -> security.sign(v, body, rpc, policy))
              .flatMap(
                  assertion -> {
                    var headers = new Metadata();
                    headers.put(
                        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                        assertion);
                    return send.apply(body, MetadataUtils.newAttachHeadersInterceptor(headers));
                  });
        });
  }
}
