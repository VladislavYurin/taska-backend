package ru.taska.projectstorage;

import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Owner continuation validates current authority plus the immutable local creation identity. */
public final class ProjectOwnerPolicy implements ProjectQuotaOwner.Policy {
  private final TenantDecisionFacts facts;
  private final ManagedDomainReads reads;
  private final RpcContextSecurity security;
  private final TenantProjectStore store;

  public ProjectOwnerPolicy(
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      RpcContextSecurity security,
      TenantProjectStore store) {
    this.facts = facts;
    this.reads = reads;
    this.security = security;
    this.store = store;
  }

  public Mono<Void> require(
      VerifiedTenantContext c, ProjectQuotaOwner.Use use, UUID operation, UUID project) {
    var claim = RpcContextBridge.details(c).getContext();
    if (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE) {
      if (!claim.getServicePurpose().equals("provisioning")
          || !c.scopes().contains("project:recover")
          || !Set.of("tenant-service", "project-service").contains(claim.getServiceIdentity()))
        return Mono.error(denied());
      var binding = facts.binding(c);
      return security
          .authorize(RpcContextBridge.details(c), binding.rpc(), binding.policy())
          .then(bound(c, operation, project, false))
          .then(use == ProjectQuotaOwner.Use.OPEN ? activeCompany(c, operation) : Mono.empty());
    }
    if (!c.scopes().contains("write") && !c.scopes().contains("read")) return Mono.error(denied());
    return Mono.zip(facts.actor(c), reads.actorMembership(c))
        .flatMap(
            primary -> {
              var member = primary.getT2();
              var actor = primary.getT1();
              boolean admin =
                  member.getItems(0).getMembership().getRole()
                          == Membership.RoleValue.ROLE_TENANT_ADMIN
                      || actor.getGlobalAdmin()
                          && actor.getWebMfa()
                          && (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                              || claim.getCredentialKind()
                                  == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
              if ((use == ProjectQuotaOwner.Use.OPEN
                      || use == ProjectQuotaOwner.Use.PROVISION
                      || use == ProjectQuotaOwner.Use.INVENTORY)
                  && !admin) return Mono.error(denied());
              if ((use == ProjectQuotaOwner.Use.OPEN || use == ProjectQuotaOwner.Use.PROVISION)
                  && member.getTenantLifecycle() != Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
                return Mono.error(
                    new DomainException(
                        DomainStatus.FAILED_PRECONDITION,
                        "Company lifecycle does not allow project opening"));
              if (use == ProjectQuotaOwner.Use.INVENTORY) {
                if (!admin) return Mono.error(denied());
                return Mono.empty();
              }
              if (operation == null || project == null) return Mono.error(denied());
              return bound(c, operation, project, true);
            });
  }

  private Mono<Void> activeCompany(VerifiedTenantContext c, UUID operation) {
    return store
        .execute(
            s ->
                s.database()
                    .sql(
                        "SELECT actor_id FROM taska.project_creation_operations WHERE tenant_id=:t"
                            + " AND operation_id=:op")
                    .bind("t", UUID.fromString(c.tenantId()))
                    .bind("op", operation)
                    .map((r, m) -> r.get("actor_id", UUID.class))
                    .one())
        .flatMap(actor -> reads.memberships(UUID.fromString(c.tenantId()), List.of(actor)))
        .flatMap(
            result ->
                result.getTenantLifecycle() == Tenant.LifecycleValue.LIFECYCLE_ACTIVE
                    ? Mono.<Void>empty()
                    : Mono.error(
                        new DomainException(
                            DomainStatus.FAILED_PRECONDITION,
                            "Company lifecycle does not allow project opening")));
  }

  private Mono<Void> bound(VerifiedTenantContext c, UUID operation, UUID project, boolean actor) {
    if (operation == null || project == null) return Mono.error(denied());
    return store
        .execute(
            s ->
                s.database()
                    .sql(
                        "SELECT actor_id,project_id FROM taska.project_creation_operations WHERE"
                            + " tenant_id=:t AND operation_id=:op")
                    .bind("t", UUID.fromString(c.tenantId()))
                    .bind("op", operation)
                    .fetch()
                    .one()
                    .filter(
                        row ->
                            project.equals(row.get("project_id"))
                                && (!actor
                                    || UUID.fromString(c.actorId()).equals(row.get("actor_id"))))
                    .switchIfEmpty(Mono.error(denied())))
        .then();
  }

  private static DomainException denied() {
    return new DomainException(
        DomainStatus.PERMISSION_DENIED, "Project operation authority required");
  }
}
