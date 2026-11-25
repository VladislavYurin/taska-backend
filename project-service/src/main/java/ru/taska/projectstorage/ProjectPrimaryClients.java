package ru.taska.projectstorage;

import java.time.Clock;
import java.util.*;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.*;
import ru.taska.quota.*;
import ru.taska.quota.ProjectQuotaProtocol.Reservation;
import ru.taska.security.tenant.*;

public final class ProjectPrimaryClients
    implements TenantProjectApi.Facets, ProjectCreation.Workflow {
  private final ManagedDomainReads reads;
  private final SignedQuotaClient workflows, issues;

  public ProjectPrimaryClients(ManagedDomainReads reads, RpcContextSecurity security) {
    this.reads = reads;
    workflows =
        new SignedQuotaClient(security, Clock.systemUTC(), "project-service", "workflow-service");
    issues = new SignedQuotaClient(security, Clock.systemUTC(), "project-service", "issue-service");
  }

  private Mono<ProjectWorkflowInitialization> initialization(Reservation r, boolean cancel) {
    String rpc =
        (cancel
                ? ProjectWorkflowBootstrapServiceGrpc.getCancelMethod()
                : ProjectWorkflowBootstrapServiceGrpc.getEnsureMethod())
            .getFullMethodName();
    return workflows.call(
        r.tenant(),
        rpc,
        cancel ? "cancelProjectWorkflow" : "initializeProjectWorkflow",
        new RpcContextSecurity.Policy(true, true),
        c ->
            ProjectInitializationRequest.newBuilder()
                .setContext(c)
                .setReservation(ProjectQuotaWire.encode(r))
                .build(),
        (q, auth) -> {
          var stub =
              ReactorProjectWorkflowBootstrapServiceGrpc.newReactorStub(
                      reads.channel("workflow-service"))
                  .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                  .withInterceptors(auth);
          return cancel ? stub.cancel(q) : stub.ensure(q);
        });
  }

  @Override
  public Mono<UUID> ensure(Reservation r) {
    return initialization(r, false).map(v -> TenantProjectApi.uuid(v.getWorkflowId()));
  }

  @Override
  public Mono<Void> cancel(Reservation r) {
    return initialization(r, true).then();
  }

  @Override
  public Mono<ProjectWorkflowFacets> workflows(UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                workflows.call(
                    UUID.fromString(c.tenantId()),
                    ProjectWorkflowBootstrapServiceGrpc.getReadMethod().getFullMethodName(),
                    "readProjectWorkflows",
                    q ->
                        ProjectFacetRequest.newBuilder()
                            .setContext(q)
                            .setProjectId(project.toString())
                            .build(),
                    (q, auth) ->
                        ReactorProjectWorkflowBootstrapServiceGrpc.newReactorStub(
                                reads.channel("workflow-service"))
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .read(q)));
  }

  @Override
  public Mono<ProjectLabelFacets> labels(UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                issues.call(
                    UUID.fromString(c.tenantId()),
                    ProjectLabelsServiceGrpc.getReadMethod().getFullMethodName(),
                    "readProjectLabels",
                    q ->
                        ProjectFacetRequest.newBuilder()
                            .setContext(q)
                            .setProjectId(project.toString())
                            .build(),
                    (q, auth) ->
                        ReactorProjectLabelsServiceGrpc.newReactorStub(
                                reads.channel("issue-service"))
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .read(q)));
  }

  @Override
  public Mono<List<ProjectMemberDetailsResponse>> profiles(List<TenantProjectApi.Grant> grants) {
    if (grants.isEmpty()) return Mono.just(List.of());
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                Flux.fromIterable(grants)
                    .buffer(100)
                    .concatMap(
                        part ->
                            reads
                                .service(
                                    "membership",
                                    "auth-service",
                                    ProjectDirectoryServiceGrpc.getProfilesMethod()
                                        .getFullMethodName(),
                                    "readProjectProfiles",
                                    UUID.fromString(c.tenantId()),
                                    Set.of("memberships:read", "profiles:read"),
                                    q ->
                                        ProjectProfileRequest.newBuilder()
                                            .setContext(q)
                                            .addAllUserIds(
                                                part.stream()
                                                    .map(g -> g.user().toString())
                                                    .toList())
                                            .build(),
                                    (q, auth) ->
                                        ReactorProjectDirectoryServiceGrpc.newReactorStub(
                                                reads.channel("auth-service"))
                                            .withDeadlineAfter(
                                                5, java.util.concurrent.TimeUnit.SECONDS)
                                            .withInterceptors(auth)
                                            .profiles(q))
                                .map(
                                    result -> {
                                      var seen = new HashSet<String>();
                                      return result.getItemsList().stream()
                                          .map(
                                              profile -> {
                                                var grant =
                                                    part.stream()
                                                        .filter(
                                                            g ->
                                                                g.user()
                                                                    .toString()
                                                                    .equals(profile.getUserId()))
                                                        .findFirst()
                                                        .orElseThrow(
                                                            () ->
                                                                new DomainException(
                                                                    DomainStatus.UNAVAILABLE,
                                                                    "Invalid directory response"));
                                                if (!seen.add(profile.getUserId()))
                                                  throw new DomainException(
                                                      DomainStatus.UNAVAILABLE,
                                                      "Duplicate directory response");
                                                return profile.toBuilder()
                                                    .setRole(
                                                        ProjectRole.valueOf(
                                                            "PROJECT_ROLE_" + grant.role()))
                                                    .setAddedAt(
                                                        com.google.protobuf.Timestamp.newBuilder()
                                                            .setSeconds(
                                                                grant.added().getEpochSecond())
                                                            .setNanos(grant.added().getNano()))
                                                    .build();
                                              })
                                          .toList();
                                    }))
                    .flatMapIterable(v -> v)
                    .collectList());
  }
}
