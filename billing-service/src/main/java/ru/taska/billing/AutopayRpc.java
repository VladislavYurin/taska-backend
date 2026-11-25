package ru.taska.billing;

import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AutopayDispatchResult;
import ru.taska.api.b2b.v1.AutopayDispatchRpcRequest;
import ru.taska.api.b2b.v1.ReactorAutopayWorkerServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

/** Внутренний вход нового списания; actor из браузера не превращается в сервисного исполнителя. */
@Service
@ConditionalOnProperty(name = "taska.billing.autopay.enabled", havingValue = "true")
public final class AutopayRpc extends ReactorAutopayWorkerServiceGrpc.AutopayWorkerServiceImplBase {
  private final AutopayCoordinator coordinator;

  public AutopayRpc(AutopayCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public Mono<AutopayDispatchResult> dispatchAutopay(Mono<AutopayDispatchRpcRequest> request) {
    return RpcContextBridge.inbound(
            request
                .flatMap(
                    q ->
                        coordinator.dispatch(
                            AutopayStore.uuid(q.getTenantId()),
                            AutopayStore.uuid(q.getInvoiceId())))
                .map(
                    a ->
                        AutopayDispatchResult.newBuilder()
                            .setAttemptId(a.id().toString())
                            .setOperationId(a.operationId().toString())
                            .setState(a.state().name())
                            .build()))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
