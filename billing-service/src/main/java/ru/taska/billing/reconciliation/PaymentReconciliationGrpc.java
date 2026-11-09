package ru.taska.billing.reconciliation;

import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
@ConditionalOnProperty(name = "taska.billing.reconciliation.enabled", havingValue = "true")
public final class PaymentReconciliationGrpc
    extends ReactorPaymentReconciliationServiceGrpc.PaymentReconciliationServiceImplBase {
  private final PaymentReconciliation reconciliation;

  public PaymentReconciliationGrpc(PaymentReconciliation reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Override
  public Mono<PaymentReconciliationResult> reconcilePaymentAttempt(
      Mono<ReconcilePaymentAttemptRequest> request) {
    return RpcContextBridge.inbound(
            request
                .flatMap(
                    r ->
                        reconciliation.process(
                            uuid(r.getTenantId()), uuid(r.getAttemptId()), uuid(r.getLeaseToken())))
                .map(
                    s ->
                        PaymentReconciliationResult.newBuilder()
                            .setState(s.state())
                            .setReasonCode(s.reason())
                            .build()))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static UUID uuid(String s) {
    var id = UUID.fromString(s);
    if (!id.toString().equals(s)) throw new IllegalArgumentException("Canonical UUID required");
    return id;
  }
}
