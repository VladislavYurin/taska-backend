package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
@Profile("tenant-storage")
public final class ProjectQuotaGrpcService
    extends ReactorProjectQuotaReadServiceGrpc.ProjectQuotaReadServiceImplBase {
  private final LifetimeTaskQuota quota;

  public ProjectQuotaGrpcService(LifetimeTaskQuota quota) {
    this.quota = quota;
  }

  @Override
  public Mono<QuotaUsage> getProjectTaskQuota(Mono<GetProjectTaskQuotaRpcRequest> request) {
    return errors(RpcContextBridge.inbound(request.flatMap(quota::project)));
  }

  @Override
  public Mono<TenantTaskQuotaSummary> getTenantTaskQuotaSummary(
      Mono<GetTenantTaskQuotaSummaryRequest> request) {
    return errors(RpcContextBridge.inbound(request.flatMap(quota::summary)));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
