package ru.taska.security.directory;

import java.util.*;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/**
 * Bounded project enrichment only; the global user directory stays closed in the native profile.
 */
@GrpcService
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class ProjectDirectoryRpc
    extends ReactorProjectDirectoryServiceGrpc.ProjectDirectoryServiceImplBase {
  private final DatabaseClient db;
  private final ManagedDomainReads reads;

  public ProjectDirectoryRpc(DatabaseClient db, ManagedDomainReads reads) {
    this.db = db;
    this.reads = reads;
  }

  @Override
  public Mono<ProjectProfileFacets> profiles(Mono<ProjectProfileRequest> input) {
    return RpcContextBridge.inbound(
            input.flatMap(
                q ->
                    VerifiedTenantContext.current()
                        .flatMap(
                            c -> {
                              var claim = RpcContextBridge.details(c).getContext();
                              if (claim.getCredentialKind()
                                      != CredentialKind.CREDENTIAL_KIND_SERVICE
                                  || !claim.getServiceIdentity().equals("project-service")
                                  || !claim.getServicePurpose().equals("membership")
                                  || !c.scopes()
                                      .containsAll(Set.of("memberships:read", "profiles:read")))
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.PERMISSION_DENIED,
                                        "Project directory authority required"));
                              if (q.getUserIdsCount() < 1 || q.getUserIdsCount() > 100)
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.INVALID_ARGUMENT, "Bounded IDs required"));
                              var ids =
                                  q.getUserIdsList().stream()
                                      .map(ProjectDirectoryRpc::id)
                                      .distinct()
                                      .toList();
                              if (ids.isEmpty() || ids.size() > 100)
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.INVALID_ARGUMENT, "Bounded IDs required"));
                              return reads
                                  .memberships(UUID.fromString(c.tenantId()), ids)
                                  .flatMap(
                                      batch -> {
                                        var active =
                                            batch.getItemsList().stream()
                                                .filter(
                                                    m ->
                                                        m.getFound()
                                                            && m.hasMembership()
                                                            && m.getMembership().getState()
                                                                == Membership.StateValue
                                                                    .STATE_ACTIVE)
                                                .map(m -> id(m.getUserId()))
                                                .toList();
                                        if (active.isEmpty())
                                          return Mono.just(
                                              ProjectProfileFacets.getDefaultInstance());
                                        return db.sql(
                                                "SELECT id,display_name,email FROM taska.users"
                                                    + " WHERE id IN (:ids) AND status='ACTIVE'"
                                                    + " ORDER BY id")
                                            .bind("ids", active)
                                            .map(
                                                (r, m) ->
                                                    ProjectMemberDetailsResponse.newBuilder()
                                                        .setUserId(
                                                            r.get("id", UUID.class).toString())
                                                        .setDisplayName(
                                                            r.get("display_name", String.class))
                                                        .setEmail(r.get("email", String.class))
                                                        .build())
                                            .all()
                                            .collectList()
                                            .map(
                                                all ->
                                                    ProjectProfileFacets.newBuilder()
                                                        .addAllItems(all)
                                                        .build());
                                      });
                            })))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static UUID id(String text) {
    try {
      var id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Canonical UUID required");
    }
  }
}
