package ru.taska.workflowstorage;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.time.*;
import java.util.*;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.capability.*;
import ru.taska.exception.*;
import ru.taska.quota.*;
import ru.taska.security.tenant.*;

/** Narrow project initializer, not the tenant workflow CRUD API. */
@GrpcService
public class ProjectWorkflowBootstrap
    extends ReactorProjectWorkflowBootstrapServiceGrpc.ProjectWorkflowBootstrapServiceImplBase {
  private final TenantWorkflowStore store;
  private final ManagedDomainReads reads;
  private final RpcTrustBackend identities;
  private final RpcContextSecurity security;
  private final GrpcProjectQuotaAuthority root;
  private final SignedQuotaClient project;
  private final ProjectAccessClient access;

  public ProjectWorkflowBootstrap(
      TenantWorkflowStore store,
      ManagedDomainReads reads,
      RpcTrustBackend identities,
      RpcContextSecurity security) {
    this.store = store;
    this.reads = reads;
    this.identities = identities;
    this.security = security;
    root =
        new GrpcProjectQuotaAuthority(
            ReactorQuotaAuthorityServiceGrpc.newReactorStub(reads.channel("tenant-service")),
            security,
            Clock.systemUTC(),
            "workflow-service");
    project =
        new SignedQuotaClient(security, Clock.systemUTC(), "workflow-service", "project-service");
    access = new ProjectAccessClient(reads, security, "workflow-service");
  }

  private Mono<Void> principal(String rpc, boolean mutation) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claim = RpcContextBridge.details(c).getContext();
              if (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE) {
                if (!claim.getServicePurpose().equals("provisioning")
                    || !c.scopes().contains("project:recover")
                    || !claim.getServiceIdentity().equals("project-service"))
                  return Mono.error(denied());
                return security
                    .authorize(
                        RpcContextBridge.details(c),
                        rpc,
                        new RpcContextSecurity.Policy(true, mutation))
                    .then();
              }
              if (!c.scopes().contains(mutation ? "write" : "read")) return Mono.error(denied());
              return Mono.zip(
                      identities.actorFacts(
                          RpcContextBridge.details(c),
                          rpc,
                          new RpcContextSecurity.Policy(true, mutation)),
                      reads.actorMembership(c))
                  .flatMap(
                      primary -> {
                        var actor = primary.getT1();
                        boolean admin =
                            primary.getT2().getItems(0).getMembership().getRole()
                                    == Membership.RoleValue.ROLE_TENANT_ADMIN
                                || actor.getGlobalAdmin()
                                    && actor.getWebMfa()
                                    && (claim.getCredentialKind()
                                            == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                                        || claim.getCredentialKind()
                                            == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
                        if (rpc.equals(
                                ProjectWorkflowBootstrapServiceGrpc.getEnsureMethod()
                                    .getFullMethodName())
                            && !admin) return Mono.error(denied());
                        return Mono.<Void>empty();
                      });
            });
  }

  private Mono<Reservation> primary(Reservation expected) {
    return root.get(expected.operation())
        .map(
            r -> {
              if (!r.sameIdentity(expected) || !r.expiresAt().equals(expected.expiresAt()))
                throw invalid();
              return r;
            });
  }

  private Mono<ProjectInitializationDescriptor> descriptor(Reservation r) {
    return project
        .call(
            r.tenant(),
            ProjectInitializationServiceGrpc.getDescribeMethod().getFullMethodName(),
            "describeProjectInitialization",
            c ->
                ProjectInitializationRequest.newBuilder()
                    .setContext(c)
                    .setReservation(ProjectQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                ReactorProjectInitializationServiceGrpc.newReactorStub(
                        reads.channel("project-service"))
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .describe(q))
        .map(
            d -> {
              if (!d.getTenantId().equals(r.tenant().toString())
                  || !d.getProjectId().equals(r.resource().toString())
                  || !d.getOperationId().equals(r.operation().toString())
                  || !d.getRequestHash().matches("[a-f0-9]{64}")
                  || d.getTypesCount() != 3
                  || !d.getTypesList().stream()
                      .map(InitializedProjectType::getCode)
                      .collect(java.util.stream.Collectors.toSet())
                      .equals(Set.of("TASK", "BUG", "STORY"))) throw invalid();
              d.getTypesList().forEach(t -> ProjectQuotaWire.uuid(t.getGeneration()));
              return d;
            });
  }

  @Override
  public Mono<ProjectWorkflowInitialization> ensure(Mono<ProjectInitializationRequest> input) {
    return errors(
        input.flatMap(
            q -> {
              var r = ProjectQuotaWire.decode(q.getReservation());
              return principal(
                      ProjectWorkflowBootstrapServiceGrpc.getEnsureMethod().getFullMethodName(),
                      true)
                  .then(primary(r))
                  .flatMap(
                      proof -> {
                        if (proof.cancelRequested()
                            || proof.state() != State.ACTIVE && proof.state() != State.COMMITTED)
                          return Mono.error(invalid());
                        return existingInitialization(r)
                            .switchIfEmpty(
                                Mono.defer(
                                    () ->
                                        descriptor(r)
                                            .flatMap(
                                                d ->
                                                    store
                                                        .execute(
                                                            s ->
                                                                lock(s, r)
                                                                    .then(receipt(s, r))
                                                                    .flatMap(
                                                                        row -> {
                                                                          matching(row, r);
                                                                          if (!d.getRequestHash()
                                                                                  .equals(
                                                                                      row.get(
                                                                                          "request_hash"))
                                                                              || !"PREPARED"
                                                                                  .equals(
                                                                                      row.get(
                                                                                          "state")))
                                                                            return Mono.error(
                                                                                invalid());
                                                                          return Mono.just(
                                                                              (UUID)
                                                                                  row.get(
                                                                                      "workflow_id"));
                                                                        })
                                                                    .switchIfEmpty(
                                                                        Mono.defer(
                                                                                () ->
                                                                                    s.copies()
                                                                                        .filter(
                                                                                            copy ->
                                                                                                copy.templateId()
                                                                                                    .equals(
                                                                                                        UUID
                                                                                                            .fromString(
                                                                                                                "11111111-1111-1111-1111-111111111111")))
                                                                                        .singleOrEmpty()
                                                                                        .switchIfEmpty(
                                                                                            Mono
                                                                                                .error(
                                                                                                    new DomainException(
                                                                                                        DomainStatus
                                                                                                            .FAILED_PRECONDITION,
                                                                                                        "Company"
                                                                                                            + " workflow"
                                                                                                            + " template"
                                                                                                            + " required"))))
                                                                            .flatMap(
                                                                                copy ->
                                                                                    s.instantiate(
                                                                                        copy.id(),
                                                                                        r
                                                                                            .resource()))
                                                                            .flatMap(
                                                                                w ->
                                                                                    Flux
                                                                                        .fromIterable(
                                                                                            d
                                                                                                .getTypesList())
                                                                                        .concatMap(
                                                                                            type ->
                                                                                                s
                                                                                                    .bind(
                                                                                                        r
                                                                                                            .resource(),
                                                                                                        type
                                                                                                            .getCode(),
                                                                                                        ProjectQuotaWire
                                                                                                            .uuid(
                                                                                                                type
                                                                                                                    .getGeneration()),
                                                                                                        w
                                                                                                            .id()))
                                                                                        .then(
                                                                                            s.database()
                                                                                                .sql(
                                                                                                    "INSERT"
                                                                                                        + " INTO"
                                                                                                        + " taska.project_initializations(tenant_id,operation_id,project_id,reservation_id,reservation_fence,reservation_expiry,request_hash,workflow_id,state)"
                                                                                                        + " VALUES(:t,:op,:p,:r,:f,:e,:h,:w,'PREPARED')")
                                                                                                .bind(
                                                                                                    "t",
                                                                                                    r
                                                                                                        .tenant())
                                                                                                .bind(
                                                                                                    "op",
                                                                                                    r
                                                                                                        .operation())
                                                                                                .bind(
                                                                                                    "p",
                                                                                                    r
                                                                                                        .resource())
                                                                                                .bind(
                                                                                                    "r",
                                                                                                    r
                                                                                                        .id())
                                                                                                .bind(
                                                                                                    "f",
                                                                                                    r
                                                                                                        .fence())
                                                                                                .bind(
                                                                                                    "e",
                                                                                                    r.expiresAt()
                                                                                                        .atOffset(
                                                                                                            ZoneOffset
                                                                                                                .UTC))
                                                                                                .bind(
                                                                                                    "h",
                                                                                                    d
                                                                                                        .getRequestHash())
                                                                                                .bind(
                                                                                                    "w",
                                                                                                    w
                                                                                                        .id())
                                                                                                .then()
                                                                                                .thenReturn(
                                                                                                    w
                                                                                                        .id())))))
                                                        .map(
                                                            id ->
                                                                ProjectWorkflowInitialization
                                                                    .newBuilder()
                                                                    .setWorkflowId(id.toString())
                                                                    .build()))));
                      });
            }));
  }

  private Mono<ProjectWorkflowInitialization> existingInitialization(Reservation r) {
    return store.execute(
        s ->
            receipt(s, r)
                .map(
                    row -> {
                      matching(row, r);
                      if (!"PREPARED".equals(row.get("state"))) throw invalid();
                      return ProjectWorkflowInitialization.newBuilder()
                          .setWorkflowId(row.get("workflow_id").toString())
                          .build();
                    }));
  }

  @Override
  public Mono<ProjectWorkflowInitialization> cancel(Mono<ProjectInitializationRequest> input) {
    return errors(
        input.flatMap(
            q -> {
              var r = ProjectQuotaWire.decode(q.getReservation());
              return principal(
                      ProjectWorkflowBootstrapServiceGrpc.getCancelMethod().getFullMethodName(),
                      true)
                  .then(primary(r))
                  .flatMap(
                      proof -> {
                        if (!proof.cancelRequested() || proof.state() == State.COMMITTED)
                          return Mono.error(invalid());
                        return store.execute(
                            s ->
                                lock(s, r)
                                    .then(receipt(s, r))
                                    .flatMap(
                                        row -> {
                                          matching(row, r);
                                          return Mono.just(row);
                                        })
                                    .defaultIfEmpty(Map.of())
                                    .flatMap(
                                        row -> {
                                          var id = (UUID) row.get("workflow_id");
                                          var write =
                                              s.database()
                                                  .sql(
                                                      "INSERT INTO"
                                                          + " taska.project_initializations(tenant_id,operation_id,project_id,reservation_id,reservation_fence,reservation_expiry,state)"
                                                          + " VALUES(:t,:op,:p,:r,:f,:e,'CANCELLED')"
                                                          + " ON CONFLICT(tenant_id,operation_id)"
                                                          + " DO UPDATE SET state='CANCELLED'")
                                                  .bind("t", r.tenant())
                                                  .bind("op", r.operation())
                                                  .bind("p", r.resource())
                                                  .bind("r", r.id())
                                                  .bind("f", r.fence())
                                                  .bind("e", r.expiresAt().atOffset(ZoneOffset.UTC))
                                                  .then();
                                          if (id == null)
                                            return write.thenReturn(
                                                ProjectWorkflowInitialization.getDefaultInstance());
                                          return write
                                              .then(
                                                  s.database()
                                                      .sql(
                                                          "DELETE FROM taska.workflow_bindings"
                                                              + " WHERE tenant_id=:t AND"
                                                              + " project_id=:p AND workflow_id=:w")
                                                      .bind("t", r.tenant())
                                                      .bind("p", r.resource())
                                                      .bind("w", id)
                                                      .then())
                                              .then(
                                                  s.database()
                                                      .sql(
                                                          "DELETE FROM"
                                                              + " taska.workflow_template_instances"
                                                              + " WHERE tenant_id=:t AND"
                                                              + " project_id=:p AND workflow_id=:w")
                                                      .bind("t", r.tenant())
                                                      .bind("p", r.resource())
                                                      .bind("w", id)
                                                      .then())
                                              .then(
                                                  s.database()
                                                      .sql(
                                                          "DELETE FROM taska.workflows WHERE"
                                                              + " tenant_id=:t AND"
                                                              + " owner_project_id=:p AND id=:w")
                                                      .bind("t", r.tenant())
                                                      .bind("p", r.resource())
                                                      .bind("w", id)
                                                      .then())
                                              .thenReturn(
                                                  ProjectWorkflowInitialization.newBuilder()
                                                      .setWorkflowId(id.toString())
                                                      .build());
                                        }));
                      });
            }));
  }

  @Override
  public Mono<ProjectWorkflowFacets> read(Mono<ProjectFacetRequest> input) {
    return errors(
        input.flatMap(
            q -> {
              UUID id = ProjectQuotaWire.uuid(q.getProjectId());
              return access
                  .check(id)
                  .then(access.types(id))
                  .flatMap(
                      types ->
                          store.execute(
                              s ->
                                  Flux.fromIterable(types.getItemsList())
                                      .concatMap(
                                          type ->
                                              s.resolve(
                                                      id,
                                                      type.getCode(),
                                                      ProjectQuotaWire.uuid(type.getGeneration()))
                                                  .switchIfEmpty(
                                                      Mono.error(
                                                          new DomainException(
                                                              DomainStatus.UNAVAILABLE,
                                                              "Project workflow binding missing")))
                                                  .flatMap(
                                                      w ->
                                                          Mono.zip(
                                                                  s.statuses(w.id()).collectList(),
                                                                  s.transitions(w.id())
                                                                      .collectList(),
                                                                  s.database()
                                                                      .sql(
                                                                          "SELECT"
                                                                              + " created_at,updated_at"
                                                                              + " FROM"
                                                                              + " taska.workflows"
                                                                              + " WHERE"
                                                                              + " tenant_id=:t AND"
                                                                              + " owner_project_id=:p"
                                                                              + " AND id=:w")
                                                                      .bind(
                                                                          "t",
                                                                          UUID.fromString(
                                                                              q.getContext()
                                                                                  .getContext()
                                                                                  .getTenantId()))
                                                                      .bind("p", id)
                                                                      .bind("w", w.id())
                                                                      .fetch()
                                                                      .one())
                                                              .map(
                                                                  v -> {
                                                                    var graph =
                                                                        WorkflowDataResponse
                                                                            .newBuilder()
                                                                            .setId(
                                                                                w.id().toString())
                                                                            .setName(w.name())
                                                                            .setVersion(w.version())
                                                                            .setCreatedAt(
                                                                                stamp(
                                                                                    ((OffsetDateTime)
                                                                                            v.getT3()
                                                                                                .get(
                                                                                                    "created_at"))
                                                                                        .toInstant()))
                                                                            .setUpdatedAt(
                                                                                stamp(
                                                                                    ((OffsetDateTime)
                                                                                            v.getT3()
                                                                                                .get(
                                                                                                    "updated_at"))
                                                                                        .toInstant()));
                                                                    v.getT1()
                                                                        .forEach(
                                                                            n ->
                                                                                graph.addStatuses(
                                                                                    WorkflowStatusResponse
                                                                                        .newBuilder()
                                                                                        .setId(
                                                                                            n.id()
                                                                                                .toString())
                                                                                        .setStatusKey(
                                                                                            n.key())
                                                                                        .setName(
                                                                                            n
                                                                                                .name())
                                                                                        .setCategory(
                                                                                            n
                                                                                                .category())
                                                                                        .setSortOrder(
                                                                                            n
                                                                                                .order())));
                                                                    v.getT2()
                                                                        .forEach(
                                                                            e ->
                                                                                graph
                                                                                    .addTransitions(
                                                                                        WorkflowTransitionResponse
                                                                                            .newBuilder()
                                                                                            .setId(
                                                                                                e.id()
                                                                                                    .toString())
                                                                                            .setFromStatusId(
                                                                                                e.from()
                                                                                                    .toString())
                                                                                            .setToStatusId(
                                                                                                e.to()
                                                                                                    .toString())
                                                                                            .setName(
                                                                                                e
                                                                                                    .name())
                                                                                            .setSortOrder(
                                                                                                e
                                                                                                    .order())));
                                                                    return ProjectWorkflowEntryResponse
                                                                        .newBuilder()
                                                                        .setIssueType(
                                                                            type.getCode())
                                                                        .setWorkflow(graph)
                                                                        .build();
                                                                  })))
                                      .collectList()
                                      .map(
                                          all ->
                                              ProjectWorkflowFacets.newBuilder()
                                                  .addAllItems(all)
                                                  .build())));
            }));
  }

  private Mono<Void> lock(TenantWorkflowStore.Session s, Reservation r) {
    return s.database()
        .sql("SELECT pg_advisory_xact_lock(hashtextextended(:k,0))")
        .bind("k", r.tenant() + "/project-init/" + r.operation())
        .then();
  }

  private Mono<Map<String, Object>> receipt(TenantWorkflowStore.Session s, Reservation r) {
    return s.database()
        .sql(
            "SELECT * FROM taska.project_initializations WHERE tenant_id=:t AND operation_id=:op"
                + " FOR UPDATE")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .fetch()
        .one();
  }

  private static void matching(Map<String, Object> row, Reservation r) {
    if (!r.resource().equals(row.get("project_id"))
        || !r.id().equals(row.get("reservation_id"))
        || r.fence() != ((Number) row.get("reservation_fence")).longValue()
        || !r.expiresAt().equals(((OffsetDateTime) row.get("reservation_expiry")).toInstant()))
      throw invalid();
  }

  private static com.google.protobuf.Timestamp stamp(Instant i) {
    return com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(i.getEpochSecond())
        .setNanos(i.getNano())
        .build();
  }

  private static DomainException denied() {
    return new DomainException(
        DomainStatus.PERMISSION_DENIED, "Project initialization authority required");
  }

  private static DomainException invalid() {
    return new DomainException(
        DomainStatus.FAILED_PRECONDITION, "Project initialization receipt mismatch");
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
