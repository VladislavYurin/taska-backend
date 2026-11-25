package ru.taska.tenant;

import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/**
 * The root independently admits new resources; accepted-operation continuation cannot reserve anew.
 */
public final class NativeProjectAdmission implements ProjectQuotaStore.Policy {
  private final TenantDecisionFacts facts;
  private final ManagedDomainReads reads;
  private final RpcContextSecurity security;
  private final TenantTransactionExecutor tx;

  public NativeProjectAdmission(
      TenantTransactionExecutor tx,
      ManagedDomainReads reads,
      RpcTrustBackend identities,
      RpcContextSecurity security) {
    this.tx = tx;
    this.reads = reads;
    this.security = security;
    var b = new HashMap<String, TenantDecisionFacts.Binding>();
    b.put(
        "reserveQuota",
        new TenantDecisionFacts.Binding(
            QuotaAuthorityServiceGrpc.getReserveQuotaMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, true)));
    b.put(
        "getQuotaReservation",
        new TenantDecisionFacts.Binding(
            QuotaAuthorityServiceGrpc.getGetQuotaReservationMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "commitQuota",
        new TenantDecisionFacts.Binding(
            QuotaAuthorityServiceGrpc.getCommitQuotaMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, true)));
    b.put(
        "releaseQuota",
        new TenantDecisionFacts.Binding(
            QuotaAuthorityServiceGrpc.getReleaseQuotaMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, true)));
    facts = new TenantDecisionFacts(tx, reads, identities, b, Clock.systemUTC());
  }

  @Override
  public Mono<ProjectQuotaStore.Grant> current(
      VerifiedTenantContext c, ProjectQuotaStore.Command command, UUID operation) {
    var claim = RpcContextBridge.details(c).getContext();
    if (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE) {
      if (command == ProjectQuotaStore.Command.RESERVE_NEW
          || command == ProjectQuotaStore.Command.MUTATION_CONTEXT
          || command == ProjectQuotaStore.Command.REPLAY
          || !claim.getServicePurpose().equals("provisioning")
          || !c.scopes().contains("project:recover")
          || !Set.of("project-service", "workflow-service", "tenant-service")
              .contains(claim.getServiceIdentity())) return Mono.error(denied());
      var binding = facts.binding(c);
      return security
          .authorize(RpcContextBridge.details(c), binding.rpc(), binding.policy())
          .then(accepted(c, operation, false, command == ProjectQuotaStore.Command.COMMIT))
          .thenReturn(grant(false));
    }
    if (command == ProjectQuotaStore.Command.RESERVE_NEW)
      return facts
          .current("write")
          .flatMap(
              f ->
                  facts
                      .decision(f, Action.PROJECT_CREATE, null, Set.of())
                      .map(d -> grant(d.businessBypass())));
    if (command == ProjectQuotaStore.Command.REPLAY || command == ProjectQuotaStore.Command.COMMIT)
      return facts
          .current("write")
          .flatMap(
              f ->
                  accepted(c, operation, true, command == ProjectQuotaStore.Command.COMMIT)
                      .then(facts.acceptedCreation(f, operation))
                      .map(d -> grant(d.businessBypass())));
    return Mono.zip(facts.actor(c), reads.actorMembership(c))
        .flatMap(
            primary -> {
              var m = primary.getT2();
              var actor = primary.getT1();
              boolean admin =
                  m.getItems(0).getMembership().getRole() == Membership.RoleValue.ROLE_TENANT_ADMIN
                      || actor.getGlobalAdmin()
                          && actor.getWebMfa()
                          && (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                              || claim.getCredentialKind()
                                  == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
              if (command == ProjectQuotaStore.Command.MUTATION_CONTEXT) {
                if (admin) return Mono.just(grant(false));
                return Mono.error(denied());
              }
              if (command == ProjectQuotaStore.Command.LOOKUP && admin)
                return Mono.just(grant(false));
              if (command == ProjectQuotaStore.Command.READ
                  || command == ProjectQuotaStore.Command.RECONCILE) {
                if (!admin) return Mono.error(denied());
                return Mono.just(grant(false));
              }
              if (command == ProjectQuotaStore.Command.RELEASE_USAGE)
                return Mono.error(
                    new DomainException(
                        DomainStatus.UNAVAILABLE, "Physical deletion not activated"));
              return accepted(c, operation, true, command == ProjectQuotaStore.Command.COMMIT)
                  .thenReturn(grant(false));
            });
  }

  private Mono<Void> accepted(VerifiedTenantContext c, UUID op, boolean actor, boolean commit) {
    if (op == null) return Mono.error(denied());
    return tx.execute(
            db ->
                db.sql(
                        "SELECT q.project_actor_id,t.lifecycle FROM taska.quota_reservations q JOIN"
                            + " taska.tenants t ON t.id=q.tenant_id WHERE q.tenant_id=:t AND"
                            + " q.operation_id=:op AND q.resource='PROJECT' AND"
                            + " q.resource_owner='project-service'")
                    .bind("t", UUID.fromString(c.tenantId()))
                    .bind("op", op)
                    .fetch()
                    .one()
                    .filter(
                        row ->
                            !actor
                                || UUID.fromString(c.actorId()).equals(row.get("project_actor_id")))
                    .switchIfEmpty(Mono.error(denied()))
                    .map(
                        row -> {
                          if (commit && !"ACTIVE".equals(row.get("lifecycle")))
                            throw new DomainException(
                                DomainStatus.FAILED_PRECONDITION,
                                "Company lifecycle does not allow project opening");
                          return row;
                        }))
        .then();
  }

  private static ProjectQuotaStore.Grant grant(boolean bypass) {
    return new ProjectQuotaStore.Grant(bypass, 1, Duration.ofMinutes(3));
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "Project admission scope denied");
  }
}
