package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdmitFreeTenantRequest;
import ru.taska.api.b2b.v1.ApplyFreeEligibilityDispositionRequest;
import ru.taska.api.b2b.v1.FreeAdmissionResult;
import ru.taska.api.b2b.v1.FreeDispositionResult;
import ru.taska.api.b2b.v1.ReactorFreeEligibilityServiceGrpc;
import ru.taska.api.b2b.v1.TenantCreationOptions;
import ru.taska.api.b2b.v1.TenantCreationOptionsRequest;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class FreeEligibilityGrpcService
    extends ReactorFreeEligibilityServiceGrpc.FreeEligibilityServiceImplBase {
  private final FreeEligibilityLedger ledger;

  public FreeEligibilityGrpcService(FreeEligibilityLedger ledger) {
    this.ledger = ledger;
  }

  private <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<TenantCreationOptions> getTenantCreationOptions(
      Mono<TenantCreationOptionsRequest> request) {
    return inbound(request.flatMap(r -> ledger.options()));
  }

  @Override
  public Mono<FreeAdmissionResult> admitFreeTenant(Mono<AdmitFreeTenantRequest> request) {
    return inbound(request.flatMap(ledger::admit));
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.FreeAdmissionReceipt> getFreeAdmissionReceipt(
      Mono<ru.taska.api.b2b.v1.GetFreeAdmissionReceiptRequest> request) {
    return inbound(request.flatMap(ledger::accepted));
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.FreeAdmissionReceipt> cancelFreeTenantAdmission(
      Mono<ru.taska.api.b2b.v1.CancelFreeTenantAdmissionRequest> request) {
    return inbound(request.flatMap(ledger::cancel));
  }

  @Override
  public Mono<FreeDispositionResult> applyFreeEligibilityDisposition(
      Mono<ApplyFreeEligibilityDispositionRequest> request) {
    return inbound(request.flatMap(r -> ledger.disposition(r.getCaseId())));
  }
}
