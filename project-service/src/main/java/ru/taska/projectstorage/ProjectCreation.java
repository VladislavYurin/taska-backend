package ru.taska.projectstorage;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.TenantDecisionFacts.Frame;
import ru.taska.exception.*;
import ru.taska.mapper.ProjectMapper;
import ru.taska.quota.*;
import ru.taska.security.tenant.*;
import ru.taska.service.ProjectInputValidator;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable retry envelope is written before the first remote reserve. Never allocates a retry slot.
 */
public final class ProjectCreation implements ProjectQuotaOwner.Initialization {
  record Operation(
      UUID tenant,
      UUID operation,
      UUID project,
      UUID actor,
      long membership,
      String hash,
      String body,
      Instant expiry,
      String state,
      UUID workflow) {}

  public interface Workflow {
    Mono<UUID> ensure(Reservation r);

    Mono<Void> cancel(Reservation r);
  }

  private final TenantProjectStore store;
  private final TenantDecisionFacts facts;
  private final Authority root;
  private final Workflow workflow;
  private final ProjectMapper mapper;
  private final ObjectMapper json;
  private final Clock clock;
  private final RpcContextSecurity security;
  private ProjectQuotaOwner owner;

  public ProjectCreation(
      TenantProjectStore store,
      TenantDecisionFacts facts,
      Authority root,
      Workflow workflow,
      ProjectMapper mapper,
      ObjectMapper json,
      Clock clock,
      RpcContextSecurity security) {
    this.store = store;
    this.facts = facts;
    this.root = root;
    this.workflow = workflow;
    this.mapper = mapper;
    this.json = json;
    this.clock = clock;
    this.security = security;
  }

  public void owner(ProjectQuotaOwner value) {
    if (owner != null) throw new IllegalStateException();
    owner = value;
  }

  public Mono<ProjectResponse> create(CreateProjectRequest q) {
    var b = q.getBody();
    new ProjectQuotaOwner.Create(
        b.getProjectKey(), b.getName(), TenantProjectApi.uuid(b.getUserId()));
    ProjectInputValidator.validate(
        Optional.of(b.getName()),
        b.hasDescription() ? Optional.of(b.getDescription()) : Optional.empty(),
        b.hasColor() ? Optional.of(b.getColor()) : Optional.empty());
    var body = new TreeMap<String, Object>();
    body.put("key", b.getProjectKey());
    body.put("name", b.getName());
    body.put("creator", b.getUserId());
    body.put(
        "description",
        !b.hasDescription() || b.getDescription().isBlank() ? null : b.getDescription());
    body.put("color", b.hasColor() ? b.getColor() : null);
    String canonical = json.writeValueAsString(body), hash = hash(canonical);
    return facts
        .current("write")
        .flatMap(
            f -> {
              TenantProjectApi.actor(f, b.getUserId());
              String identity =
                  q.hasIdempotencyKey()
                      ? TenantProjectApi.uuid(q.getIdempotencyKey()).toString()
                      : f.context().requestId();
              UUID op =
                  UUID.nameUUIDFromBytes(
                      (f.context().tenantId() + "/project/" + identity)
                          .getBytes(StandardCharsets.UTF_8));
              return facts
                  .creationPrincipal(f)
                  .then(
                      store
                          .execute(
                              s ->
                                  operation(
                                      s.database(), UUID.fromString(f.context().tenantId()), op))
                          .flatMap(
                              existing -> {
                                if (!existing.actor().toString().equals(f.context().actorId())
                                    || !existing.hash().equals(hash)) return Mono.error(conflict());
                                return resumeDecision(f, existing)
                                    .flatMap(
                                        d ->
                                            store.execute(
                                                session ->
                                                    audit(session.database(), f, existing, d)))
                                    .thenReturn(existing);
                              })
                          .switchIfEmpty(
                              Mono.defer(
                                  () ->
                                      facts
                                          .decision(f, Action.PROJECT_CREATE, null, Set.of())
                                          .flatMap(
                                              d ->
                                                  store.execute(
                                                      s -> {
                                                        var t =
                                                            UUID.fromString(f.context().tenantId());
                                                        var id = UUID.randomUUID();
                                                        var expiry =
                                                            clock
                                                                .instant()
                                                                .plusSeconds(120)
                                                                .truncatedTo(ChronoUnit.MICROS);
                                                        return s.database()
                                                            .sql(
                                                                "INSERT INTO"
                                                                    + " taska.project_creation_operations(tenant_id,operation_id,project_id,actor_id,actor_membership_generation,request_hash,request,reservation_expiry)"
                                                                    + " VALUES(:t,:op,:p,:a,:g,:h,CAST(:q"
                                                                    + " AS jsonb),:e) ON"
                                                                    + " CONFLICT(tenant_id,operation_id)"
                                                                    + " DO NOTHING")
                                                            .bind("t", t)
                                                            .bind("op", op)
                                                            .bind("p", id)
                                                            .bind(
                                                                "a",
                                                                UUID.fromString(
                                                                    f.context().actorId()))
                                                            .bind(
                                                                "g",
                                                                Long.parseLong(
                                                                    f.membership().getVersion()))
                                                            .bind("h", hash)
                                                            .bind("q", canonical)
                                                            .bind(
                                                                "e",
                                                                expiry.atOffset(ZoneOffset.UTC))
                                                            .then()
                                                            .then(operation(s.database(), t, op))
                                                            .map(
                                                                created -> {
                                                                  if (!created
                                                                          .actor()
                                                                          .toString()
                                                                          .equals(
                                                                              f.context().actorId())
                                                                      || !created
                                                                          .hash()
                                                                          .equals(hash))
                                                                    throw conflict();
                                                                  return created;
                                                                })
                                                            .flatMap(
                                                                created ->
                                                                    audit(
                                                                            s.database(),
                                                                            f,
                                                                            created,
                                                                            d)
                                                                        .thenReturn(created));
                                                      }))))
                          .flatMap(this::run));
            });
  }

  private Mono<Void> audit(
      DatabaseClient db, Frame frame, Operation op, CapabilityEvaluator.Decision decision) {
    if (!decision.auditRequired()) return Mono.empty();
    return db.sql(
            "INSERT INTO"
                + " taska.project_authorization_audit(tenant_id,actor_id,action,project_id,request_id,policy_version,decision)"
                + " VALUES(:t,:actor,'project.create',:p,:r,:v,CAST(:decision AS jsonb))")
        .bind("t", op.tenant())
        .bind("actor", UUID.fromString(frame.context().actorId()))
        .bind("p", op.project())
        .bind("r", frame.context().requestId())
        .bind("v", CapabilityEvaluator.POLICY_VERSION)
        .bind(
            "decision",
            json.writeValueAsString(
                Map.of(
                    "operationId",
                    op.operation(),
                    "allowed",
                    true,
                    "businessBypass",
                    decision.businessBypass(),
                    "flagVersion",
                    frame.configuration().snapshot().getVersion(),
                    "entitlementVersion",
                    frame.entitlement().getVersion(),
                    "restrictions",
                    decision.bypassedRestrictions().stream().map(Enum::name).toList())))
        .then();
  }

  private Mono<CapabilityEvaluator.Decision> resumeDecision(Frame frame, Operation op) {
    return root.get(op.operation())
        .flatMap(
            proof -> {
              matching(op, proof);
              if (proof.cancelRequested()
                  || proof.state() != State.ACTIVE && proof.state() != State.COMMITTED)
                return Mono.error(
                    new DomainException(
                        DomainStatus.FAILED_PRECONDITION, "Creation operation closed"));
              return facts.acceptedCreation(frame, op.operation());
            })
        .onErrorResume(
            error ->
                io.grpc.Status.fromThrowable(error).getCode() == io.grpc.Status.Code.NOT_FOUND
                    ? facts.decision(frame, Action.PROJECT_CREATE, null, Set.of())
                    : Mono.error(error));
  }

  private static void matching(Operation op, Reservation r) {
    if (!op.tenant().equals(r.tenant())
        || !op.project().equals(r.resource())
        || !op.operation().equals(r.operation())
        || !op.expiry().equals(r.expiresAt())) throw conflict();
  }

  private Mono<Void> continuation(VerifiedTenantContext c) {
    var claim = RpcContextBridge.details(c).getContext();
    if (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE) {
      if (!claim.getServiceIdentity().equals("project-service")
          || !claim.getServicePurpose().equals("provisioning")
          || !c.scopes().containsAll(Set.of("write", "project:recover")))
        return Mono.error(
            new DomainException(DomainStatus.PERMISSION_DENIED, "Recovery authority required"));
      var b = facts.binding(c);
      return security.authorize(RpcContextBridge.details(c), b.rpc(), b.policy()).then();
    }
    return facts.access(c);
  }

  public Mono<ProjectResponse> recover(UUID op) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              Mono<?> principal =
                  c.actorId() == null
                      ? continuation(c)
                      : facts.current("write").flatMap(f -> facts.creationPrincipal(f));
              return principal
                  .then(
                      store.execute(
                          s -> operation(s.database(), UUID.fromString(c.tenantId()), op)))
                  .switchIfEmpty(
                      Mono.error(
                          new DomainException(
                              DomainStatus.NOT_FOUND, "Creation operation not found")))
                  .flatMap(
                      o -> {
                        if (c.actorId() != null && !o.actor().toString().equals(c.actorId()))
                          return Mono.error(
                              new DomainException(
                                  DomainStatus.PERMISSION_DENIED, "Operation owner required"));
                        return root.get(op)
                            .flatMap(
                                r -> {
                                  matching(o, r);
                                  Mono<?> decision =
                                      c.actorId() == null
                                          ? Mono.just(true)
                                          : facts
                                              .current("write")
                                              .flatMap(f -> facts.acceptedCreation(f, op));
                                  return decision.then(Mono.defer(() -> run(o, r)));
                                });
                      });
            });
  }

  public Mono<List<UUID>> recoveryWorklist() {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                continuation(c)
                    .then(
                        store.execute(
                            s ->
                                s.database()
                                    .sql(
                                        "WITH picked AS (SELECT operation_id FROM"
                                            + " taska.project_creation_operations WHERE"
                                            + " tenant_id=:t AND state IN ('CREATING','CANCELLING')"
                                            + " ORDER BY"
                                            + " recovery_checked_at,created_at,operation_id LIMIT"
                                            + " 100 FOR UPDATE SKIP LOCKED) UPDATE"
                                            + " taska.project_creation_operations o SET"
                                            + " recovery_checked_at=clock_timestamp() FROM picked"
                                            + " WHERE o.tenant_id=:t AND"
                                            + " o.operation_id=picked.operation_id RETURNING"
                                            + " o.operation_id")
                                    .bind("t", UUID.fromString(c.tenantId()))
                                    .map((r, m) -> r.get("operation_id", UUID.class))
                                    .all()
                                    .collectList())));
  }

  private Mono<ProjectResponse> run(Operation op) {
    if (op.state().equals("CANCELLED"))
      return Mono.error(
          new DomainException(DomainStatus.FAILED_PRECONDITION, "Creation cancelled"));
    var proof =
        op.state().equals("CANCELLING")
            ? root.get(op.operation())
            : root.reserve(op.operation(), op.project(), op.expiry());
    return proof.flatMap(r -> run(op, r));
  }

  private Mono<ProjectResponse> run(Operation op, Reservation r) {
    if (!op.tenant().equals(r.tenant())
        || !op.project().equals(r.resource())
        || !op.operation().equals(r.operation())
        || !op.expiry().equals(r.expiresAt())) return Mono.error(conflict());
    if (op.state().equals("CANCELLED"))
      return Mono.error(
          new DomainException(DomainStatus.FAILED_PRECONDITION, "Creation cancelled"));
    if (r.state() != State.COMMITTED
        && (r.cancelRequested()
            || op.state().equals("CANCELLING")
            || !clock.instant().isBefore(r.expiresAt())))
      return root.release(r)
          .then(
              Mono.error(
                  new DomainException(DomainStatus.FAILED_PRECONDITION, "Creation cancelled")));
    if (r.state() != State.ACTIVE && r.state() != State.COMMITTED)
      return Mono.error(
          new DomainException(DomainStatus.FAILED_PRECONDITION, "Reservation closed"));
    var body = json.readTree(op.body());
    var command =
        new ProjectQuotaOwner.Create(
            body.path("key").asText(), body.path("name").asText(), op.actor());
    Mono<?> provision =
        VerifiedTenantContext.current()
            .flatMap(
                c ->
                    RpcContextBridge.details(c).getContext().getCredentialKind()
                            == CredentialKind.CREDENTIAL_KIND_SERVICE
                        ? ensure(r)
                        : owner.provision(r, command));
    return provision
        .then(r.state() == State.COMMITTED ? Mono.just(r) : root.commit(r))
        .flatMap(owner::open)
        .then(
            store.execute(
                s ->
                    s.find(op.project())
                        .switchIfEmpty(
                            Mono.error(
                                new DomainException(
                                    DomainStatus.UNAVAILABLE, "Project opening incomplete")))))
        .map(p -> mapper.toProjectResponse(p, ru.taska.domain.ProjectRole.ADMIN))
        .onErrorMap(ProjectCreation::definiteFailure)
        .onErrorResume(error -> cancelOnDefiniteFailure(op, r, error));
  }

  private static Throwable definiteFailure(Throwable error) {
    if (error instanceof org.springframework.dao.DuplicateKeyException)
      return new DomainException(DomainStatus.ALREADY_EXISTS, "Project already exists");
    if (error instanceof io.grpc.StatusRuntimeException status) {
      DomainStatus code =
          switch (status.getStatus().getCode()) {
            case NOT_FOUND -> DomainStatus.NOT_FOUND;
            case ALREADY_EXISTS -> DomainStatus.ALREADY_EXISTS;
            case INVALID_ARGUMENT -> DomainStatus.INVALID_ARGUMENT;
            case FAILED_PRECONDITION -> DomainStatus.FAILED_PRECONDITION;
            default -> null;
          };
      if (code != null) return new DomainException(code, "Project initialization rejected");
    }
    return error;
  }

  private Mono<ProjectResponse> cancelOnDefiniteFailure(
      Operation op, Reservation r, Throwable error) {
    // Transport/timeout may have committed remotely. Retain the envelope and quota for recovery.
    if (!(error instanceof DomainException d)
        || !Set.of(
                DomainStatus.INVALID_ARGUMENT,
                DomainStatus.ALREADY_EXISTS,
                DomainStatus.NOT_FOUND,
                DomainStatus.FAILED_PRECONDITION)
            .contains(d.getStatus())) return Mono.error(error);
    return root.get(r.operation())
        .flatMap(
            current -> {
              if (current.state() == State.COMMITTED) return Mono.error(error);
              return store
                  .execute(
                      s ->
                          s.database()
                              .sql(
                                  "UPDATE taska.project_creation_operations SET state='CANCELLING'"
                                      + " WHERE tenant_id=:t AND operation_id=:op AND"
                                      + " state='CREATING'")
                              .bind("t", op.tenant())
                              .bind("op", op.operation())
                              .then())
                  .then(root.release(r))
                  .onErrorResume(cancel -> Mono.empty())
                  .then(Mono.error(error));
            });
  }

  public Mono<ProjectInitializationDescriptor> describe(Reservation r) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!r.tenant().toString().equals(c.tenantId()))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "Tenant mismatch"));
              Mono<?> principal =
                  c.actorId() == null
                      ? continuation(c)
                      : facts.current("write").flatMap(f -> facts.creationPrincipal(f));
              return principal
                  .then(
                      root.get(r.operation())
                          .map(
                              proof -> {
                                if (!proof.sameIdentity(r)
                                    || !proof.expiresAt().equals(r.expiresAt())
                                    || proof.cancelRequested()
                                    || proof.state() != State.ACTIVE
                                        && proof.state() != State.COMMITTED) throw conflict();
                                return proof;
                              }))
                  .then(
                      c.actorId() == null
                          ? Mono.just(true)
                          : facts
                              .current("write")
                              .flatMap(f -> facts.acceptedCreation(f, r.operation())))
                  .then(
                      store.execute(
                          s ->
                              operation(s.database(), r.tenant(), r.operation())
                                  .flatMap(
                                      op -> {
                                        if (!op.project().equals(r.resource())
                                            || c.actorId() != null
                                                && !op.actor().toString().equals(c.actorId())
                                            || !op.state().equals("CREATING"))
                                          return Mono.error(conflict());
                                        return s.types()
                                            .list(op.project())
                                            .map(
                                                t ->
                                                    InitializedProjectType.newBuilder()
                                                        .setCode(t.code())
                                                        .setGeneration(t.generation().toString())
                                                        .build())
                                            .collectList()
                                            .map(
                                                types ->
                                                    ProjectInitializationDescriptor.newBuilder()
                                                        .setTenantId(op.tenant().toString())
                                                        .setProjectId(op.project().toString())
                                                        .setOperationId(op.operation().toString())
                                                        .setCreatorId(op.actor().toString())
                                                        .setRequestHash(op.hash())
                                                        .addAllTypes(types)
                                                        .build());
                                      })));
            });
  }

  @Override
  public Mono<Void> ensure(Reservation r) {
    return root.get(r.operation())
        .flatMap(
            primary -> {
              if (!primary.sameIdentity(r)
                  || !primary.expiresAt().equals(r.expiresAt())
                  || primary.cancelRequested()
                  || primary.state() != State.ACTIVE && primary.state() != State.COMMITTED)
                return Mono.error(conflict());
              return store
                  .execute(
                      s ->
                          operation(s.database(), r.tenant(), r.operation())
                              .flatMap(
                                  op -> {
                                    if (op.state().equals("CANCELLING")
                                        && primary.state() == State.COMMITTED)
                                      return s.database()
                                          .sql(
                                              "UPDATE taska.project_creation_operations SET"
                                                  + " state='CREATING' WHERE tenant_id=:t AND"
                                                  + " operation_id=:op AND state='CANCELLING'")
                                          .bind("t", r.tenant())
                                          .bind("op", r.operation())
                                          .then()
                                          .then(operation(s.database(), r.tenant(), r.operation()));
                                    return Mono.just(op);
                                  }))
                  .switchIfEmpty(
                      Mono.error(
                          new DomainException(
                              DomainStatus.FAILED_PRECONDITION, "Creation envelope required")))
                  .flatMap(
                      op -> {
                        if (!op.project().equals(r.resource())
                            || op.state().equals("CANCELLING")
                            || op.state().equals("CANCELLED")) return Mono.error(conflict());
                        if (op.workflow() != null) return Mono.empty();
                        return store
                            .execute(
                                s ->
                                    s.database()
                                        .sql(
                                            "SELECT id FROM taska.projects WHERE tenant_id=:t AND"
                                                + " id=:p AND admission_state='PROVISIONING' FOR"
                                                + " UPDATE")
                                        .bind("t", r.tenant())
                                        .bind("p", r.resource())
                                        .fetch()
                                        .one()
                                        .switchIfEmpty(Mono.error(conflict()))
                                        .then(
                                            s.types()
                                                .list(r.resource())
                                                .hasElements()
                                                .flatMap(
                                                    has ->
                                                        has
                                                            ? Mono.empty()
                                                            : s.types().seedDefaults(r.resource())))
                                        .then(
                                            s.types().syncLegacySettings(r.resource(), op.actor()))
                                        .then(
                                            s.database()
                                                .sql(
                                                    "UPDATE taska.project_members SET"
                                                        + " root_membership_version=:g WHERE"
                                                        + " tenant_id=:t AND project_id=:p AND"
                                                        + " user_id=:a AND root_membership_version"
                                                        + " IS NULL")
                                                .bind("t", r.tenant())
                                                .bind("p", r.resource())
                                                .bind("a", op.actor())
                                                .bind("g", op.membership())
                                                .then()))
                            .then(workflow.ensure(r))
                            .flatMap(
                                id ->
                                    store.execute(
                                        s ->
                                            s.database()
                                                .sql(
                                                    "UPDATE taska.project_creation_operations SET"
                                                        + " initialized_at=now(),workflow_id=:w"
                                                        + " WHERE tenant_id=:t AND operation_id=:op"
                                                        + " AND initialized_at IS NULL AND"
                                                        + " state='CREATING'")
                                                .bind("t", r.tenant())
                                                .bind("op", r.operation())
                                                .bind("w", id)
                                                .then()));
                      });
            });
  }

  @Override
  public Mono<Void> ready(Reservation r) {
    return store
        .execute(
            s ->
                operation(s.database(), r.tenant(), r.operation())
                    .filter(
                        o ->
                            o.project().equals(r.resource())
                                && o.workflow() != null
                                && Set.of("CREATING", "OPEN").contains(o.state()))
                    .switchIfEmpty(
                        Mono.error(
                            new DomainException(
                                DomainStatus.UNAVAILABLE, "INITIALIZATION_PENDING"))))
        .then();
  }

  @Override
  public Mono<Void> cancel(Reservation r) {
    return workflow
        .cancel(r)
        .then(
            store.execute(
                s ->
                    s.database()
                        .sql(
                            "UPDATE taska.project_creation_operations SET state='CANCELLED' WHERE"
                                + " tenant_id=:t AND operation_id=:op AND project_id=:p AND state"
                                + " IN ('CREATING','CANCELLING')")
                        .bind("t", r.tenant())
                        .bind("op", r.operation())
                        .bind("p", r.resource())
                        .then()));
  }

  @Override
  public Mono<Void> opened(DatabaseClient db, Reservation r) {
    return lockedOperation(db, r.tenant(), r.operation())
        .flatMap(
            op -> {
              if (op.state().equals("OPEN")) return Mono.empty();
              if (op.workflow() == null || !op.state().equals("CREATING"))
                return Mono.error(conflict());
              var body = json.readTree(op.body());
              var patch =
                  db.sql(
                          "UPDATE taska.projects SET description=:d,color=:c WHERE tenant_id=:t AND"
                              + " id=:p")
                      .bind("t", r.tenant())
                      .bind("p", r.resource());
              patch =
                  body.path("description").isNull()
                      ? patch.bindNull("d", String.class)
                      : patch.bind("d", body.path("description").asText());
              patch =
                  body.path("color").isNull()
                      ? patch.bindNull("c", String.class)
                      : patch.bind("c", body.path("color").asText());
              return patch
                  .then()
                  .then(
                      db.sql(
                              "INSERT INTO"
                                  + " taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload)"
                                  + " VALUES(:t,'project',:p,'ProjectCreated',CAST(:payload AS"
                                  + " jsonb))")
                          .bind("t", r.tenant())
                          .bind("p", r.resource())
                          .bind(
                              "payload",
                              json.writeValueAsString(
                                  new ru.taska.event.payload.projectService.ProjectCreatedPayload(
                                      r.resource(), body.path("key").asText(), op.actor())))
                          .then())
                  .then(
                      db.sql(
                              "UPDATE taska.project_creation_operations SET state='OPEN' WHERE"
                                  + " tenant_id=:t AND operation_id=:op AND state='CREATING'")
                          .bind("t", r.tenant())
                          .bind("op", r.operation())
                          .fetch()
                          .rowsUpdated()
                          .flatMap(rows -> rows == 1 ? Mono.empty() : Mono.error(conflict())));
            });
  }

  private Mono<Operation> lockedOperation(DatabaseClient db, UUID tenant, UUID op) {
    return operation(db, tenant, op, true);
  }

  private Mono<Operation> operation(DatabaseClient db, UUID tenant, UUID op) {
    return operation(db, tenant, op, false);
  }

  private Mono<Operation> operation(DatabaseClient db, UUID tenant, UUID op, boolean lock) {
    return db.sql(
            "SELECT *,request::text body FROM taska.project_creation_operations WHERE tenant_id=:t"
                + " AND operation_id=:op"
                + (lock ? " FOR UPDATE" : ""))
        .bind("t", tenant)
        .bind("op", op)
        .map(
            (r, m) ->
                new Operation(
                    tenant,
                    op,
                    r.get("project_id", UUID.class),
                    r.get("actor_id", UUID.class),
                    r.get("actor_membership_generation", Long.class),
                    r.get("request_hash", String.class),
                    r.get("body", String.class),
                    r.get("reservation_expiry", OffsetDateTime.class).toInstant(),
                    r.get("state", String.class),
                    r.get("workflow_id", UUID.class)))
        .one();
  }

  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static DomainException conflict() {
    return new DomainException(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
  }
}
