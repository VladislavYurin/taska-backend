package ru.taska.billing;

import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class PaymentSettlementGrpc
    extends ReactorPaymentSettlementServiceGrpc.PaymentSettlementServiceImplBase {
  private final PaymentSettlement settlement;

  public PaymentSettlementGrpc(PaymentSettlement settlement) {
    this.settlement = settlement;
  }

  @Override
  public Mono<PaymentSettlementResult> applyVerifiedPayment(
      Mono<ApplyVerifiedPaymentRequest> request) {
    return inbound(
        request.flatMap(r -> settlement.apply(uuid(r.getTenantId()), uuid(r.getEvidenceId()))));
  }

  @Override
  public Mono<InitialPaymentDecision> getInitialPaymentDecision(
      Mono<GetInitialPaymentDecisionRequest> request) {
    return inbound(
        request.flatMap(
            r -> settlement.initialDecision(uuid(r.getTenantId()), uuid(r.getDecisionId()))));
  }

  private static UUID uuid(String value) {
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw CatalogErrors.invalid();
      return id;
    } catch (IllegalArgumentException e) {
      throw CatalogErrors.invalid();
    }
  }

  private static <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
