package ru.taska.billing;

import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Delegates the original credential; only a separately authorized worker can read recovery proof.
 */
public final class GrpcFreePlanAdmission implements PlanChangeDependencies.FreeAuthority {
  private final ReactorFreeEligibilityServiceGrpc.ReactorFreeEligibilityServiceStub stub;
  private final SignedQuotaClient client;

  public GrpcFreePlanAdmission(
      ReactorFreeEligibilityServiceGrpc.ReactorFreeEligibilityServiceStub stub,
      RpcContextSecurity security,
      Clock clock) {
    this.stub = Objects.requireNonNull(stub);
    this.client = new SignedQuotaClient(security, clock, "billing-service", "tenant-service");
  }

  @Override
  public Mono<PlanChangeDependencies.FreeProof> admit(
      UUID tenant, UUID operation, UUID owner, long version) {
    return client
        .call(
            tenant,
            FreeEligibilityServiceGrpc.getAdmitFreeTenantMethod().getFullMethodName(),
            "admitFreeTenant",
            new RpcContextSecurity.Policy(true, true),
            c ->
                AdmitFreeTenantRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(tenant.toString())
                    .setOperationId(operation.toString())
                    .setExpectedOwnerVersion(Long.toString(version))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .admitFreeTenant(Mono.just(q)))
        .map(
            r -> {
              if (!r.hasReceipt() || r.getAdmitted() != r.getReceipt().getAdmitted())
                throw new DomainException(
                    DomainStatus.UNAVAILABLE, "FREE_AUTHORITY_PROOF_REQUIRED");
              return decode(r.getReceipt(), tenant, operation, owner, version);
            })
        .onErrorMap(GrpcFreePlanAdmission::error);
  }

  @Override
  public Mono<PlanChangeDependencies.FreeProof> accepted(
      UUID tenant, UUID operation, UUID owner, long version) {
    return client
        .call(
            tenant,
            FreeEligibilityServiceGrpc.getGetFreeAdmissionReceiptMethod().getFullMethodName(),
            "getFreeAdmissionReceipt",
            c ->
                GetFreeAdmissionReceiptRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(tenant.toString())
                    .setOperationId(operation.toString())
                    .setOwnerUserId(owner.toString())
                    .setOwnerVersion(Long.toString(version))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .getFreeAdmissionReceipt(Mono.just(q)))
        .map(r -> decode(r, tenant, operation, owner, version))
        .onErrorMap(GrpcFreePlanAdmission::error);
  }

  @Override
  public Mono<PlanChangeDependencies.FreeProof> cancel(
      UUID tenant, UUID operation, UUID owner, long version) {
    return client
        .call(
            tenant,
            FreeEligibilityServiceGrpc.getCancelFreeTenantAdmissionMethod().getFullMethodName(),
            "cancelFreeTenantAdmission",
            new RpcContextSecurity.Policy(true, true),
            c ->
                CancelFreeTenantAdmissionRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(tenant.toString())
                    .setOperationId(operation.toString())
                    .setOwnerUserId(owner.toString())
                    .setOwnerVersion(Long.toString(version))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .cancelFreeTenantAdmission(Mono.just(q)))
        .map(r -> decode(r, tenant, operation, owner, version))
        .onErrorMap(GrpcFreePlanAdmission::error);
  }

  private static PlanChangeDependencies.FreeProof decode(
      FreeAdmissionReceipt r, UUID tenant, UUID op, UUID owner, long version) {
    try {
      if (!tenant.toString().equals(r.getTenantId())
          || !op.toString().equals(r.getOperationId())
          || !owner.toString().equals(r.getOwnerUserId())
          || !Long.toString(version).equals(r.getOwnerVersion()))
        throw new IllegalArgumentException();
      Instant.parse(r.getAdmitted() ? r.getAcceptedAt() : r.getCancelledAt());
      return new PlanChangeDependencies.FreeProof(tenant, op, owner, version, r.getAdmitted());
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.DATA_LOSS, "FREE_ADMISSION_IDENTITY_CONFLICT");
    }
  }

  private static Throwable error(Throwable t) {
    if (t instanceof DomainException) return t;
    if (t instanceof StatusRuntimeException g) {
      var status =
          switch (g.getStatus().getCode()) {
            case NOT_FOUND -> DomainStatus.NOT_FOUND;
            case PERMISSION_DENIED, UNAUTHENTICATED -> DomainStatus.PERMISSION_DENIED;
            case ABORTED -> DomainStatus.ABORTED;
            case ALREADY_EXISTS -> DomainStatus.ALREADY_EXISTS;
            case FAILED_PRECONDITION -> DomainStatus.FAILED_PRECONDITION;
            default -> DomainStatus.UNAVAILABLE;
          };
      return new DomainException(
          status,
          g.getStatus().getDescription() != null
                  && g.getStatus().getDescription().contains("FREE_ELIGIBILITY_USED")
              ? "FREE_ELIGIBILITY_USED"
              : "FREE_ADMISSION_UNAVAILABLE");
    }
    return new DomainException(DomainStatus.UNAVAILABLE, "FREE_ADMISSION_UNAVAILABLE");
  }
}
