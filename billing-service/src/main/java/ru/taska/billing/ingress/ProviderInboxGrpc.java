package ru.taska.billing.ingress;

import com.google.protobuf.Empty;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
@ConditionalOnProperty(name = "taska.billing.ingress.enabled", havingValue = "true")
public final class ProviderInboxGrpc
    extends ReactorProviderInboxServiceGrpc.ProviderInboxServiceImplBase {
  private final ProviderInbox inbox;

  public ProviderInboxGrpc(ProviderInbox inbox) {
    this.inbox = inbox;
  }

  @Override
  public Mono<ProviderInboxReceipt> observeProviderWebhook(
      Mono<ObserveProviderWebhookRequest> request) {
    return inbound(
        request
            .flatMap(
                r ->
                    inbox.receive(
                        identity(r.getProvider(), r.getProviderAccountId(), r.getEnvironment()),
                        r.getRawBody().toByteArray(),
                        r.getSignature()))
            .map(ProviderInboxGrpc::response));
  }

  @Override
  public Mono<Empty> registerProviderPayment(Mono<RegisterProviderPaymentRequest> request) {
    return inbound(
        request
            .flatMap(
                r ->
                    inbox.bind(
                        uuid(r.getTenantId()),
                        uuid(r.getInvoiceId()),
                        identity(r.getProvider(), r.getProviderAccountId(), r.getEnvironment()),
                        r.getProviderPaymentId()))
            .thenReturn(Empty.getDefaultInstance()));
  }

  @Override
  public Mono<ProviderInboxReceipt> processProviderWebhook(
      Mono<ProcessProviderWebhookRequest> request) {
    return inbound(
        request
            .flatMap(r -> inbox.process(uuid(r.getTenantId()), uuid(r.getReceiptId())))
            .map(ProviderInboxGrpc::response));
  }

  private static ProviderWebhookRegistry.Identity identity(
      String provider, String account, ProviderEnvironment environment) {
    var e =
        switch (environment) {
          case PROVIDER_ENVIRONMENT_TEST -> PaymentProvider.Environment.TEST;
          case PROVIDER_ENVIRONMENT_LIVE -> PaymentProvider.Environment.LIVE;
          default ->
              throw ProviderWebhookRegistry.failure(
                  DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
        };
    return new ProviderWebhookRegistry.Identity(provider, account, e);
  }

  private static UUID uuid(String s) {
    try {
      var id = UUID.fromString(s);
      if (!id.toString().equals(s)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw ProviderWebhookRegistry.failure(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private static ProviderInboxReceipt response(ProviderInboxStore.Receipt r) {
    var b =
        ProviderInboxReceipt.newBuilder()
            .setId(r.id().toString())
            .setState(r.state())
            .setReasonCode(r.reason());
    if (r.evidenceId() != null) b.setEvidenceId(r.evidenceId().toString());
    return b.build();
  }

  private static <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
