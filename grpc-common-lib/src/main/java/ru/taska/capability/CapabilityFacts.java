package ru.taska.capability;

import java.time.Instant;
import java.util.*;
import ru.taska.api.b2b.v1.*;

/** Immutable current facts supplied by primary authorities, never deserialized from an API body. */
public record CapabilityFacts(
    UUID actor,
    Target target,
    String requestId,
    Instant validUntil,
    Identity identity,
    MembershipSnapshot membership,
    ProjectRole projectRole,
    Tenant.LifecycleValue lifecycle,
    boolean resourceResolved,
    boolean ownsResource,
    AdminGrant adminGrant,
    Entitlement entitlement,
    PlanKind planKind,
    Set<RestrictionCode> independentRestrictions,
    Map<Flag, Boolean> flags,
    String flagVersion,
    Channel channel,
    PaymentChannel paymentChannel,
    boolean controlNotification,
    Map<Action, Set<ErrorCode>> integrity,
    Map<Action, Set<ErrorCode>> businessDenials,
    Accepted accepted,
    Worker worker) {
  public enum Channel {
    DIRECT,
    MCP
  }

  public enum PaymentChannel {
    NONE,
    WIRE,
    PSP
  }

  public enum ProjectRole {
    NONE,
    ADMIN,
    MEMBER,
    VIEWER
  }

  public enum PlanKind {
    UNKNOWN,
    FREE,
    PAID
  }

  public enum Flag {
    READ_WHILE_RESTRICTED,
    AUTOPAY,
    PAID_TENANT_TRANSFER,
    OWNER_PAUSE,
    INVITATIONS,
    PROJECT_CREATION,
    ATTACHMENT_UPLOAD,
    EXPORT,
    MCP,
    WORKFLOW_CONFIGURATION,
    TELEGRAM,
    EMAIL,
    ISSUE_TYPE_CONFIGURATION,
    PUBLIC_SIGNUP,
    PERSONAL_API_TOKENS,
    INBOX,
    SOCIAL_SIGN_IN,
    AVATAR_UPLOAD,
    WIRE_PAYMENT,
    PSP_PAYMENT,
    FREE_MCP,
    FREE_PERSONAL_API_TOKENS
  }

  public record Target(UUID tenant, UUID project, UUID resource, UUID operation) {}

  public record Identity(
      boolean active,
      boolean globalAdmin,
      String actorGeneration,
      boolean credentialActive,
      String credentialId,
      String sessionId,
      boolean webMfa,
      Set<String> scopes) {
    public Identity {
      Objects.requireNonNull(actorGeneration);
      scopes = Set.copyOf(scopes);
    }
  }

  /** Durable owner receipt; action-specific feature flag only, never a channel/grant bypass. */
  public record Accepted(
      UUID operation, Target target, Set<Action> actions, Set<Flag> pinnedFlags) {
    public Accepted {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(target);
      actions = Set.copyOf(actions);
      pinnedFlags = Set.copyOf(pinnedFlags);
    }
  }

  /** Online service authority + durable operation owner admission, bound to this exact scope. */
  public record Worker(
      String issuer,
      String purpose,
      String requiredScope,
      Target target,
      Set<Action> actions,
      Instant validUntil) {
    public Worker {
      Objects.requireNonNull(issuer);
      Objects.requireNonNull(purpose);
      Objects.requireNonNull(requiredScope);
      Objects.requireNonNull(target);
      actions = Set.copyOf(actions);
      Objects.requireNonNull(validUntil);
    }
  }

  public CapabilityFacts {
    Objects.requireNonNull(target);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(validUntil);
    Objects.requireNonNull(identity);
    Objects.requireNonNull(projectRole);
    Objects.requireNonNull(lifecycle);
    Objects.requireNonNull(planKind);
    Objects.requireNonNull(flagVersion);
    if (flagVersion.isBlank()) throw new IllegalArgumentException("Current flag version required");
    Objects.requireNonNull(channel);
    Objects.requireNonNull(paymentChannel);
    independentRestrictions = Set.copyOf(independentRestrictions);
    flags = Map.copyOf(flags);
    if (independentRestrictions.contains(RestrictionCode.UNRECOGNIZED)
        || independentRestrictions.contains(RestrictionCode.RESTRICTION_CODE_UNSPECIFIED))
      throw new IllegalArgumentException("Known restriction reasons required");
    var safe = new EnumMap<Action, Set<ErrorCode>>(Action.class);
    integrity.forEach((key, value) -> safe.put(key, Set.copyOf(value)));
    integrity = Map.copyOf(safe);
    var business = new EnumMap<Action, Set<ErrorCode>>(Action.class);
    businessDenials.forEach((key, value) -> business.put(key, Set.copyOf(value)));
    businessDenials = Map.copyOf(business);
    var businessCodes =
        Set.of(
            ErrorCode.ERROR_CODE_QUOTA_EXCEEDED,
            ErrorCode.ERROR_CODE_READ_ONLY,
            ErrorCode.ERROR_CODE_CAPABILITY_DISABLED,
            ErrorCode.ERROR_CODE_OWNER_REQUIRED,
            ErrorCode.ERROR_CODE_SCOPE_DENIED,
            ErrorCode.ERROR_CODE_FREE_ELIGIBILITY_USED);
    for (var failures : businessDenials.values())
      if (!businessCodes.containsAll(failures))
        throw new IllegalArgumentException("Integrity and dependency failures cannot be bypassed");
    for (var failures : integrity.values())
      if (failures.contains(ErrorCode.ERROR_CODE_QUOTA_EXCEEDED)
          || failures.contains(ErrorCode.ERROR_CODE_READ_ONLY)
          || failures.contains(ErrorCode.ERROR_CODE_CAPABILITY_DISABLED))
        throw new IllegalArgumentException(
            "Business restrictions must not be classified as integrity");
  }
}
