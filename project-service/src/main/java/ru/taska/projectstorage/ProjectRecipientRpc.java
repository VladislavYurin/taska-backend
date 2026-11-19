package ru.taska.projectstorage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MembershipValidationBatch;
import ru.taska.api.b2b.v1.ProjectDirectoryServiceGrpc;
import ru.taska.api.b2b.v1.ProjectProfileRequest;
import ru.taska.api.b2b.v1.ProjectRecipientsRequest;
import ru.taska.api.b2b.v1.ProjectRecipientsResponse;
import ru.taska.api.b2b.v1.ReactorProjectDirectoryServiceGrpc;
import ru.taska.api.b2b.v1.ReactorProjectRecipientServiceGrpc;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Primary-проверка получателей: возвращаются только UUID, профиль не покидает границу сервиса. */
@GrpcService
public final class ProjectRecipientRpc
    extends ReactorProjectRecipientServiceGrpc.ProjectRecipientServiceImplBase {
  private final TenantProjectStore store;
  private final ManagedDomainReads reads;
  private final PlatformConfigurationSnapshotCache flags;

  public ProjectRecipientRpc(TenantProjectStore store, ManagedDomainReads reads) {
    this.store = store;
    this.reads = reads;
    flags =
        new PlatformConfigurationSnapshotCache(
            reads::configuration,
            (c, e) -> Mono.just(false),
            Clock.systemUTC(),
            Duration.ofSeconds(30));
  }

  @Override
  public Mono<ProjectRecipientsResponse> check(Mono<ProjectRecipientsRequest> input) {
    return RpcContextBridge.inbound(input.flatMap(this::resolve))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private Mono<ProjectRecipientsResponse> resolve(ProjectRecipientsRequest q) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claim = RpcContextBridge.details(c).getContext();
              if (claim.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !claim.getIssuerService().equals("issue-service")
                  || !claim.getServiceIdentity().equals("issue-service")
                  || !claim.getServicePurpose().equals("membership")
                  || !c.scopes().containsAll(Set.of("memberships:read", "profiles:read")))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "RECIPIENT_AUTHORITY_REQUIRED"));
              if (q.getUserIdsCount() < 1 || q.getUserIdsCount() > 100)
                return Mono.error(invalid());
              UUID project = id(q.getProjectId()), tenant = UUID.fromString(c.tenantId());
              List<UUID> ids =
                  q.getUserIdsList().stream().map(ProjectRecipientRpc::id).distinct().toList();
              return store
                  .execute(
                      s ->
                          s.find(project)
                              .switchIfEmpty(
                                  Mono.error(
                                      new DomainException(
                                          DomainStatus.NOT_FOUND, "PROJECT_NOT_FOUND")))
                              .thenMany(
                                  s.database()
                                      .sql(
                                          "SELECT user_id,root_membership_version FROM"
                                              + " taska.project_members WHERE tenant_id=:t AND"
                                              + " project_id=:p AND user_id IN (:ids)")
                                      .bind("t", tenant)
                                      .bind("p", project)
                                      .bind("ids", ids)
                                      .map(
                                          (r, m) ->
                                              new ProjectGrant(
                                                  r.get("user_id", UUID.class),
                                                  r.get("root_membership_version", Long.class)))
                                      .all())
                              .collectList())
                  .flatMap(
                      grants ->
                          reads
                              .memberships(tenant, ids)
                              .flatMap(
                                  batch -> {
                                    if (batch.getTenantLifecycle()
                                        != Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
                                      return Mono.just(
                                          ProjectRecipientsResponse.getDefaultInstance());
                                    var allowed =
                                        batch.getItemsList().stream()
                                            .filter(
                                                row ->
                                                    row.getFound()
                                                        && row.hasMembership()
                                                        && membershipAllowed(
                                                            row.getMembership(),
                                                            grants.stream()
                                                                .filter(
                                                                    g ->
                                                                        g.user()
                                                                            .toString()
                                                                            .equals(
                                                                                row.getUserId()))
                                                                .map(ProjectGrant::generation)
                                                                .filter(java.util.Objects::nonNull)
                                                                .findFirst()
                                                                .orElse(null)))
                                            .map(row -> row.getUserId())
                                            .toList();
                                    if (allowed.isEmpty())
                                      return Mono.just(
                                          ProjectRecipientsResponse.getDefaultInstance());
                                    Mono<Boolean> content = Mono.just(true);
                                    if (q.getContentRead())
                                      content =
                                          Mono.zip(
                                                  flags.resolve(
                                                      PlatformConfigurationSnapshotCache.Use
                                                          .ADMISSION),
                                                  reads.entitlement(tenant, 0))
                                              .map(
                                                  pair ->
                                                      contentAllowed(
                                                          tenant,
                                                          batch,
                                                          pair.getT2(),
                                                          pair.getT1(),
                                                          Instant.now()));
                                    return content.flatMap(
                                        canRead -> {
                                          if (!canRead)
                                            return Mono.just(
                                                ProjectRecipientsResponse.getDefaultInstance());
                                          return reads
                                              .service(
                                                  "membership",
                                                  "auth-service",
                                                  ProjectDirectoryServiceGrpc.getProfilesMethod()
                                                      .getFullMethodName(),
                                                  "readProjectProfiles",
                                                  tenant,
                                                  Set.of("memberships:read", "profiles:read"),
                                                  context ->
                                                      ProjectProfileRequest.newBuilder()
                                                          .setContext(context)
                                                          .addAllUserIds(allowed)
                                                          .build(),
                                                  (request, auth) ->
                                                      ReactorProjectDirectoryServiceGrpc
                                                          .newReactorStub(
                                                              reads.channel("auth-service"))
                                                          .withInterceptors(auth)
                                                          .withDeadlineAfter(
                                                              5,
                                                              java.util.concurrent.TimeUnit.SECONDS)
                                                          .profiles(request))
                                              .map(
                                                  result -> {
                                                    var seen = new HashSet<String>();
                                                    for (var profile : result.getItemsList())
                                                      if (!allowed.contains(profile.getUserId())
                                                          || !seen.add(profile.getUserId()))
                                                        throw new DomainException(
                                                            DomainStatus.UNAVAILABLE,
                                                            "INVALID_RECIPIENT_PROFILES");
                                                    return ProjectRecipientsResponse.newBuilder()
                                                        .addAllUserIds(
                                                            allowed.stream()
                                                                .filter(seen::contains)
                                                                .toList())
                                                        .build();
                                                  });
                                        });
                                  }));
            });
  }

  private record ProjectGrant(UUID user, Long generation) {}

  static boolean membershipAllowed(MembershipSnapshot member, Long projectGeneration) {
    if (member.getState() != Membership.StateValue.STATE_ACTIVE) return false;
    String version = member.getVersion();
    try {
      if (!version.matches("[1-9][0-9]{0,18}")
          || Long.parseLong(version) < 1
          || (member.getRole() != Membership.RoleValue.ROLE_MEMBER
              && member.getRole() != Membership.RoleValue.ROLE_TENANT_ADMIN))
        throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.UNAVAILABLE, "INVALID_RECIPIENT_MEMBERSHIP");
    }
    return member.getIsOwner()
        || member.getRole() == Membership.RoleValue.ROLE_TENANT_ADMIN
        || projectGeneration != null && version.equals(projectGeneration.toString());
  }

  static boolean contentAllowed(
      UUID tenant,
      MembershipValidationBatch members,
      Entitlement source,
      PlatformConfigurationSnapshotCache.Lease flags,
      Instant now) {
    var e = EntitlementProjection.validate(source);
    if (!tenant.toString().equals(e.getTenantId()))
      throw new DomainException(DomainStatus.UNAVAILABLE, "RECIPIENT_ENTITLEMENT_MISMATCH");
    boolean current =
        !now.isBefore(Instant.parse(e.getValidFrom()))
            && (!e.hasValidUntil() || now.isBefore(Instant.parse(e.getValidUntil())))
            && (!e.hasPaidThrough() || now.isBefore(Instant.parse(e.getPaidThrough())));
    return (current
            && members.getIndependentRestrictionsCount() == 0
            && e.getRestrictionsCount() == 0)
        || Boolean.TRUE.equals(flags.flags().get(Flag.READ_WHILE_RESTRICTED));
  }

  private static UUID id(String text) {
    try {
      var id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static DomainException invalid() {
    return new DomainException(
        DomainStatus.INVALID_ARGUMENT, "BOUNDED_CANONICAL_RECIPIENTS_REQUIRED");
  }
}
