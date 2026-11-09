package ru.taska.security.pat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Capability;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityEvaluator;
import ru.taska.capability.CapabilityFacts;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/**
 * pat.create through the shared capability policy: tenant web session, current ACTIVE membership,
 * lifecycle, PERSONAL_API_TOKENS and, on a FREE plan, FREE_PERSONAL_API_TOKENS for this tenant.
 */
public final class TenantPersonalTokenAdmission {
  private final CapabilityEvaluator evaluator;

  public TenantPersonalTokenAdmission(CapabilityEvaluator evaluator) {
    this.evaluator = Objects.requireNonNull(evaluator);
  }

  /**
   * Must run inside the verified request context; the evaluator re-reads current facts. The
   * GLOBAL_SELF resource is the actor's own account, scoped to the tenant the token will bind.
   */
  public Mono<Void> admit(UUID tenant, UUID actor) {
    return evaluator
        .evaluate(Action.PAT_CREATE, new CapabilityFacts.Target(tenant, null, actor, null))
        .flatMap(
            decision ->
                decision.capability().getAllowed()
                    ? Mono.<Void>empty()
                    : Mono.error(denied(decision.capability())))
        // Authority outage is 503 DEPENDENCY_UNAVAILABLE, never a stale allow.
        .onErrorMap(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.UNAVAILABLE
                    && !domain.getDetails().containsKey("code"),
            error ->
                new DomainException(
                    DomainStatus.UNAVAILABLE,
                    error.getMessage(),
                    Map.of("code", "DEPENDENCY_UNAVAILABLE")));
  }

  // Status per contracts README §6; the first denial code is the reported reason.
  static DomainException denied(Capability capability) {
    ErrorCode code =
        capability.getDenialCodesCount() == 0
            ? ErrorCode.ERROR_CODE_DEPENDENCY_UNAVAILABLE
            : capability.getDenialCodes(0);
    if (code == ErrorCode.UNRECOGNIZED || code == ErrorCode.ERROR_CODE_UNSPECIFIED)
      code = ErrorCode.ERROR_CODE_DEPENDENCY_UNAVAILABLE;
    DomainStatus status =
        switch (code) {
          case ERROR_CODE_AUTHENTICATION_REQUIRED, ERROR_CODE_TOKEN_REVOKED ->
              DomainStatus.UNAUTHENTICATED;
          case ERROR_CODE_TENANT_REQUIRED, ERROR_CODE_VALIDATION_FAILED ->
              DomainStatus.INVALID_ARGUMENT;
          case ERROR_CODE_TENANT_NOT_FOUND, ERROR_CODE_RESOURCE_NOT_FOUND -> DomainStatus.NOT_FOUND;
          case ERROR_CODE_IDEMPOTENCY_CONFLICT,
              ERROR_CODE_INVALID_STATE,
              ERROR_CODE_VERSION_CONFLICT ->
              DomainStatus.ABORTED;
          case ERROR_CODE_QUOTA_EXCEEDED,
              ERROR_CODE_LAST_ADMIN,
              ERROR_CODE_OWNER_MEMBERSHIP_PROTECTED,
              ERROR_CODE_FREE_ELIGIBILITY_USED,
              ERROR_CODE_PAYMENT_PENDING ->
              DomainStatus.FAILED_PRECONDITION;
          case ERROR_CODE_RATE_LIMITED -> DomainStatus.RESOURCE_EXHAUSTED;
          case ERROR_CODE_DEPENDENCY_UNAVAILABLE, ERROR_CODE_POLICY_NOT_CONFIGURED ->
              DomainStatus.UNAVAILABLE;
          default -> DomainStatus.PERMISSION_DENIED;
        };
    String name = code.name().substring("ERROR_CODE_".length());
    return new DomainException(status, name, Map.of("code", name));
  }
}
