package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdmitFreeTenantRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.FreeAdmissionResult;
import ru.taska.api.b2b.v1.FreeDispositionResult;
import ru.taska.api.b2b.v1.FreeEligibilityState;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.TenantCreationOptions;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.tenant.FreeEligibilityDependencies.ConfirmedEmail;
import ru.taska.tenant.FreeEligibilityDependencies.CreationPlans;
import ru.taska.tenant.FreeEligibilityDependencies.Disposition;
import ru.taska.tenant.FreeEligibilityDependencies.DispositionAction;
import ru.taska.tenant.FreeEligibilityDependencies.DispositionAuthority;
import ru.taska.tenant.FreeEligibilityDependencies.HmacAuthority;
import ru.taska.tenant.FreeEligibilityDependencies.IdentityAuthority;
import ru.taska.tenant.FreeEligibilityDependencies.KeySet;
import ru.taska.tenant.FreeEligibilityDependencies.MarkerKey;
import ru.taska.tenant.FreeEligibilityDependencies.PurposeAssessment;
import ru.taska.tenant.FreeEligibilityDependencies.PurposeAuthority;
import ru.taska.tenant.RegistryDependencies.FreeAdmission;

/** Global purpose-bound ledger; external authority/KMS work is always outside SQL transactions. */
public final class FreeEligibilityLedger implements FreeAdmission {
  private record Identity(UUID actor, List<MarkerKey> digests, KeySet keys) {}

  private record Prepared(Identity identity, PurposeAssessment purpose) {}

  private record Owner(UUID id, long version, String lifecycle, UUID creationOperation) {}

  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final TenantTransactionExecutor tenants;
  private final IdentityAuthority identities;
  private final HmacAuthority hmac;
  private final PurposeAuthority purposes;
  private final DispositionAuthority dispositions;
  private final CreationPlans plans;
  private final Clock clock;

  public FreeEligibilityLedger(
      DatabaseClient db,
      TransactionalOperator tx,
      TenantTransactionExecutor tenants,
      IdentityAuthority identities,
      HmacAuthority hmac,
      PurposeAuthority purposes,
      DispositionAuthority dispositions,
      CreationPlans plans,
      Clock clock) {
    this.db = db;
    this.tx = tx;
    this.tenants = tenants;
    this.identities = identities;
    this.hmac = hmac;
    this.purposes = purposes;
    this.dispositions = dispositions;
    this.plans = plans;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static DomainException policy() {
    return fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
  }

  private static Throwable dependency(Throwable e) {
    return e instanceof DomainException
        ? e
        : fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
  }

  private static long version(String s) {
    try {
      if (!s.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
      return Long.parseLong(s);
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private static Instant yearAfter(Instant t) {
    return t.atOffset(ZoneOffset.UTC).plusYears(1).toInstant();
  }

  private Mono<PurposeAssessment> purpose() {
    return Mono.defer(purposes::current)
        .onErrorMap(FreeEligibilityLedger::dependency)
        .switchIfEmpty(Mono.error(policy()))
        .map(
            p -> {
              Instant now = now();
              if (p.version() == null
                  || !p.version().matches("[A-Za-z0-9._:-]{1,120}")
                  || p.approvedAt() == null
                  || p.validUntil() == null
                  || p.approvedAt().isAfter(now)
                  || !p.validUntil().isAfter(now)
                  || p.validUntil().isAfter(yearAfter(p.approvedAt()))
                  || p.validUntil().getNano() % 1000 != 0) throw policy();
              return p;
            });
  }

  private Mono<Identity> identity(UUID actor) {
    return Mono.defer(() -> identities.confirmed(actor))
        .onErrorMap(FreeEligibilityLedger::dependency)
        .switchIfEmpty(Mono.error(policy()))
        .flatMap(e -> fingerprint(actor, e));
  }

  private Mono<Identity> fingerprint(UUID actor, ConfirmedEmail e) {
    return Mono.defer(
        () -> {
          if (!actor.equals(e.userId())
              || !"AUTH_EMAIL_V1".equals(e.canonicalizationVersion())
              || e.canonicalEmail() == null
              || e.canonicalEmail().isBlank()
              || e.canonicalEmail().length() > 320
              || !e.canonicalEmail()
                  .equals(e.canonicalEmail().strip().toLowerCase(java.util.Locale.ROOT))
              || e.canonicalEmail().codePoints().anyMatch(Character::isISOControl)
              || !e.canonicalEmail().contains("@")) throw policy();
          byte[] message =
              ("taska/FREE_ELIGIBILITY/email/AUTH_EMAIL_V1\0" + e.canonicalEmail())
                  .getBytes(StandardCharsets.UTF_8);
          return Mono.defer(hmac::keys)
              .onErrorMap(FreeEligibilityLedger::dependency)
              .switchIfEmpty(Mono.error(policy()))
              .flatMap(
                  keys -> {
                    if (keys.activeVersion() < 1
                        || !keys.matchingVersions().contains(keys.activeVersion())
                        || keys.matchingVersions().isEmpty()
                        || keys.matchingVersions().size() > 32
                        || keys.matchingVersions().stream().anyMatch(v -> v < 1)) throw policy();
                    return Flux.fromIterable(keys.matchingVersions().stream().sorted().toList())
                        .concatMap(
                            v ->
                                Mono.defer(() -> hmac.sign(v, message.clone()))
                                    .onErrorMap(FreeEligibilityLedger::dependency)
                                    .switchIfEmpty(Mono.error(policy()))
                                    .map(
                                        bytes -> {
                                          if (bytes.length != 32) throw policy();
                                          return new MarkerKey(v, bytes);
                                        }))
                        .collectList()
                        .map(ds -> new Identity(actor, List.copyOf(ds), keys));
                  });
        });
  }

  private Mono<Prepared> prepareIdentity(UUID actor) {
    return Mono.zip(identity(actor), purpose()).map(t -> new Prepared(t.getT1(), t.getT2()));
  }

  @Override
  public Mono<FreeAdmission> prepare(UUID actor) {
    return prepareIdentity(actor)
        .map(
            p ->
                (connection, actualActor, tenant) -> {
                  if (!actor.equals(actualActor))
                    return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED"));
                  return VerifiedTenantContext.current()
                      .flatMap(
                          v -> {
                            if (!tenant.toString().equals(v.tenantId())
                                || !actor.toString().equals(v.actorId())
                                || !v.action().equals("createTenant"))
                              return Mono.error(
                                  fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                            return owner(connection, tenant)
                                .flatMap(
                                    o -> {
                                      if (!actor.equals(o.id())
                                          || !"PROVISIONING".equals(o.lifecycle()))
                                        throw fail(
                                            DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED");
                                      return locked(
                                          connection,
                                          () ->
                                              consume(
                                                  connection, p, tenant, o.creationOperation()));
                                    });
                          });
                });
  }

  @Override
  public Mono<Void> reserve(DatabaseClient transaction, UUID actor, UUID tenant) {
    return Mono.error(
        fail(DomainStatus.FAILED_PRECONDITION, "FREE_ADMISSION_PREPARATION_REQUIRED"));
  }

  private static <T> Mono<T> locked(DatabaseClient connection, Supplier<Mono<T>> work) {
    return connection
        .sql("SELECT pg_advisory_xact_lock(hashtextextended('taska:freeEligibility',0))")
        .fetch()
        .all()
        .then(Mono.defer(work));
  }

  private Mono<Void> keysPresent(DatabaseClient connection, Identity identity) {
    return connection
        .sql("SELECT DISTINCT digest_key_version FROM taska.free_eligibility_ledger")
        .map((r, m) -> r.get("digest_key_version", Integer.class))
        .all()
        .all(identity.keys().matchingVersions()::contains)
        .flatMap(ok -> ok ? Mono.empty() : Mono.error(policy()));
  }

  private Mono<List<Map<String, Object>>> matches(DatabaseClient connection, Identity identity) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM taska.free_eligibility_ledger WHERE first_user_id=:actor");
    for (int i = 0; i < identity.digests().size(); i++)
      sql.append(" OR (digest_key_version=:v")
          .append(i)
          .append(" AND email_digest=:d")
          .append(i)
          .append(")");
    var q = connection.sql(sql.toString()).bind("actor", identity.actor());
    for (int i = 0; i < identity.digests().size(); i++) {
      var k = identity.digests().get(i);
      q = q.bind("v" + i, k.keyVersion()).bind("d" + i, k.digest());
    }
    return q.fetch().all().collectList();
  }

  private void review(List<Map<String, Object>> rows, PurposeAssessment purpose) {
    if (!purpose.validUntil().isAfter(now())
        || rows.stream()
            .anyMatch(
                r ->
                    !r.get("purpose_version").equals(purpose.version())
                        || !((OffsetDateTime) r.get("review_due_at")).toInstant().isAfter(now())))
      throw policy();
  }

  private static List<Map<String, Object>> ownOrEmail(List<Map<String, Object>> rows, UUID actor) {
    var own = rows.stream().filter(r -> actor.equals(r.get("first_user_id"))).toList();
    return own.isEmpty() ? rows : own;
  }

  private Mono<Void> insert(
      DatabaseClient connection,
      MarkerKey key,
      UUID actor,
      UUID tenant,
      UUID operation,
      Instant used,
      String purpose,
      Instant due) {
    var q =
        connection
            .sql(
                "INSERT INTO"
                    + " taska.free_eligibility_ledger(email_digest,digest_key_version,first_user_id,bound_tenant_id,consumed_at,operation_id,purpose_version,review_due_at)"
                    + " VALUES(:digest,:version,:actor,:tenant,:used,:operation,:purpose,:due)")
            .bind("digest", key.digest())
            .bind("version", key.keyVersion())
            .bind("actor", actor)
            .bind("tenant", tenant)
            .bind("used", used.atOffset(ZoneOffset.UTC))
            .bind("purpose", purpose)
            .bind("due", due.atOffset(ZoneOffset.UTC));
    q = operation == null ? q.bindNull("operation", UUID.class) : q.bind("operation", operation);
    return q.fetch().rowsUpdated().then();
  }

  private static boolean digestMatch(Map<String, Object> row, Identity identity) {
    java.nio.ByteBuffer buf = (java.nio.ByteBuffer) row.get("email_digest");
    byte[] bytes = new byte[buf.remaining()];
    buf.duplicate().get(bytes);
    return identity.digests().stream()
        .anyMatch(
            d ->
                d.keyVersion() == ((Number) row.get("digest_key_version")).intValue()
                    && java.security.MessageDigest.isEqual(d.digest(), bytes));
  }

  private Mono<Void> alias(
      DatabaseClient connection,
      Identity identity,
      List<Map<String, Object>> all,
      List<Map<String, Object>> own) {
    if (own.isEmpty() || all.stream().anyMatch(r -> digestMatch(r, identity))) return Mono.empty();
    var first =
        own.stream()
            .min(
                java.util.Comparator.comparing(
                    r -> ((OffsetDateTime) r.get("consumed_at")).toInstant()))
            .orElseThrow();
    Instant due =
        own.stream()
            .map(r -> ((OffsetDateTime) r.get("review_due_at")).toInstant())
            .min(Instant::compareTo)
            .orElseThrow();
    MarkerKey active =
        identity.digests().stream()
            .filter(k -> k.keyVersion() == identity.keys().activeVersion())
            .findFirst()
            .orElseThrow();
    return insert(
        connection,
        active,
        identity.actor(),
        (UUID) first.get("bound_tenant_id"),
        null,
        ((OffsetDateTime) first.get("consumed_at")).toInstant(),
        first.get("purpose_version").toString(),
        due);
  }

  private Mono<Void> consume(
      DatabaseClient connection, Prepared prepared, UUID tenant, UUID operation) {
    Identity identity = prepared.identity();
    return keysPresent(connection, identity)
        .then(
            connection
                .sql(
                    "SELECT bound_tenant_id,first_user_id FROM taska.free_eligibility_ledger WHERE"
                        + " operation_id=:operation")
                .bind("operation", operation)
                .fetch()
                .one()
                .doOnNext(
                    r -> {
                      if (!tenant.equals(r.get("bound_tenant_id"))
                          || !identity.actor().equals(r.get("first_user_id")))
                        throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
                    })
                .then())
        .then(matches(connection, identity))
        .flatMap(
            all -> {
              var selected = ownOrEmail(all, identity.actor());
              review(selected, prepared.purpose());
              if (!selected.isEmpty()) {
                if (selected.stream()
                    .anyMatch(
                        r ->
                            !tenant.equals(r.get("bound_tenant_id"))
                                || (r.get("first_user_id") != null
                                    && !identity.actor().equals(r.get("first_user_id")))))
                  return Mono.error(fail(DomainStatus.ALREADY_EXISTS, "FREE_ELIGIBILITY_USED"));
                return adoptLiveBinding(connection, identity, tenant, selected)
                    .then(alias(connection, identity, all, selected));
              }
              MarkerKey active =
                  identity.digests().stream()
                      .filter(k -> k.keyVersion() == identity.keys().activeVersion())
                      .findFirst()
                      .orElseThrow();
              Instant used = now(),
                  due =
                      yearAfter(used).isBefore(prepared.purpose().validUntil())
                          ? yearAfter(used)
                          : prepared.purpose().validUntil();
              if (!due.isAfter(used)) throw policy();
              return insert(
                  connection,
                  active,
                  identity.actor(),
                  tenant,
                  operation,
                  used,
                  prepared.purpose().version(),
                  due);
            });
  }

  private Mono<Owner> owner(DatabaseClient connection, UUID tenant) {
    return connection
        .sql(
            "SELECT owner_id,owner_version,lifecycle,creation_operation_id FROM taska.tenants WHERE"
                + " id=:id FOR UPDATE")
        .bind("id", tenant)
        .map(
            (r, m) ->
                new Owner(
                    r.get("owner_id", UUID.class),
                    r.get("owner_version", Long.class),
                    r.get("lifecycle", String.class),
                    r.get("creation_operation_id", UUID.class)))
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private Mono<VerifiedTenantContext> authority(String action, String issuer, UUID tenant) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!v.action().equals(action)
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !c.getIssuerService().equals(issuer)
                  || !v.scopes()
                      .containsAll(
                          Set.of(
                              "write",
                              action.equals("admitFreeTenant") ? "free:admit" : "free:retention"))
                  || (tenant == null
                      ? v.tenantId() != null
                      : !tenant.toString().equals(v.tenantId())))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return v;
            });
  }

  private Mono<Void> adoptLiveBinding(
      DatabaseClient connection,
      Identity identity,
      UUID tenant,
      List<Map<String, Object>> selected) {
    // A deleted account may return with the same verified email and regain ownership of this
    // still-live company. Bind its new user id before it can change email again.
    return Flux.fromIterable(selected)
        .filter(r -> r.get("first_user_id") == null)
        .concatMap(
            r ->
                connection
                    .sql(
                        "UPDATE taska.free_eligibility_ledger SET first_user_id=:actor"
                            + " WHERE digest_key_version=:version AND email_digest=:digest"
                            + " AND bound_tenant_id=:tenant AND first_user_id IS NULL")
                    .bind("actor", identity.actor())
                    .bind("tenant", tenant)
                    .bind("version", r.get("digest_key_version"))
                    .bind("digest", r.get("email_digest"))
                    .fetch()
                    .rowsUpdated())
        .then();
  }

  private Mono<FreeAdmissionResult> admissionReceipt(
      DatabaseClient connection,
      UUID tenant,
      UUID operation,
      Owner owner,
      Prepared prepared,
      long expected) {
    byte[] hash =
        TenantRegistryService.digest(
            "admitFreeTenant", tenant.toString(), owner.id().toString(), Long.toString(expected));
    return connection
        .sql(
            "SELECT request_hash FROM taska.idempotency_records WHERE tenant_id=:tenant AND"
                + " actor_id=:actor AND operation_code='admitFreeTenant' AND key=:key")
        .bind("tenant", tenant)
        .bind("actor", owner.id())
        .bind("key", operation.toString())
        .map((r, m) -> r.get("request_hash", byte[].class))
        .one()
        .map(
            old -> {
              if (!java.security.MessageDigest.isEqual(old, hash))
                throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
              return FreeAdmissionResult.newBuilder().setAdmitted(true).build();
            })
        .switchIfEmpty(
            Mono.defer(
                () ->
                    consume(connection, prepared, tenant, operation)
                        .then(
                            connection
                                .sql(
                                    "INSERT INTO taska.idempotency_records"
                                        + "(tenant_id,actor_id,operation_code,key,request_hash,operation_id,state,response,created_at,expires_at)"
                                        + " VALUES(:tenant,:actor,'admitFreeTenant',:key,:hash,:operation,'SUCCEEDED','{\"admitted\":true}',:now,:expires)")
                                .bind("tenant", tenant)
                                .bind("actor", owner.id())
                                .bind("key", operation.toString())
                                .bind("hash", hash)
                                .bind("operation", operation)
                                .bind("now", now().atOffset(ZoneOffset.UTC))
                                .bind("expires", now().plusSeconds(86400).atOffset(ZoneOffset.UTC))
                                .fetch()
                                .rowsUpdated())
                        .thenReturn(FreeAdmissionResult.newBuilder().setAdmitted(true).build())));
  }

  public Mono<FreeAdmissionResult> admit(AdmitFreeTenantRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = TenantRegistryService.uuid(request.getTenantId()),
              operation = TenantRegistryService.uuid(request.getOperationId());
          long expected = version(request.getExpectedOwnerVersion());
          return authority("admitFreeTenant", "billing-service", tenant)
              .flatMap(v -> tenants.execute(connection -> owner(connection, tenant)))
              .flatMap(
                  before -> {
                    if (before.version() != expected)
                      throw fail(DomainStatus.ABORTED, "VERSION_CONFLICT");
                    return prepareIdentity(before.id());
                  })
              .flatMap(
                  p ->
                      tenants.execute(
                          connection ->
                              owner(connection, tenant)
                                  .flatMap(
                                      current -> {
                                        if (current.version() != expected
                                            || !current.id().equals(p.identity().actor()))
                                          throw fail(DomainStatus.ABORTED, "VERSION_CONFLICT");
                                        if (!"ACTIVE".equals(current.lifecycle()))
                                          throw fail(
                                              DomainStatus.FAILED_PRECONDITION, "INVALID_STATE");
                                        return locked(
                                            connection,
                                            () ->
                                                admissionReceipt(
                                                    connection,
                                                    tenant,
                                                    operation,
                                                    current,
                                                    p,
                                                    expected));
                                      })));
        });
  }

  public Mono<TenantCreationOptions> options() {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!v.action().equals("getTenantCreationOptions")
                  || v.tenantId() != null
                  || v.actorId() == null
                  || !v.scopes().contains("read")
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !c.hasSessionId()
                  || c.getSessionId().isBlank()
                  || c.getActorGeneration().isBlank())
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return Mono.zip(
                      prepareIdentity(TenantRegistryService.uuid(v.actorId())),
                      Mono.defer(plans::published)
                          .onErrorMap(FreeEligibilityLedger::dependency)
                          .switchIfEmpty(Mono.error(policy())))
                  .flatMap(
                      t -> {
                        var p = t.getT1();
                        var catalog = t.getT2();
                        if (catalog.size() > 100
                            || catalog.isEmpty()
                            || catalog.stream()
                                .anyMatch(
                                    plan ->
                                        plan.getState() != PlanVersion.StateValue.STATE_PUBLISHED))
                          throw policy();
                        return tx.transactional(
                            locked(
                                db,
                                () ->
                                    keysPresent(db, p.identity())
                                        .then(matches(db, p.identity()))
                                        .map(
                                            rows -> {
                                              var selected = ownOrEmail(rows, p.identity().actor());
                                              review(selected, p.purpose());
                                              return TenantCreationOptions.newBuilder()
                                                  .addAllPlans(catalog)
                                                  .setFreeEligibility(
                                                      selected.isEmpty()
                                                          ? FreeEligibilityState
                                                              .FREE_ELIGIBILITY_AVAILABLE
                                                          : FreeEligibilityState
                                                              .FREE_ELIGIBILITY_USED)
                                                  .build();
                                            })));
                      });
            });
  }

  private Mono<Boolean> receipt(DatabaseClient connection, Disposition d) {
    return connection
        .sql(
            "SELECT decision_version,action FROM taska.free_eligibility_dispositions WHERE"
                + " case_id=:id")
        .bind("id", d.caseId())
        .fetch()
        .one()
        .map(
            r -> {
              if (!r.get("decision_version").equals(d.decisionVersion())
                  || !r.get("action").equals(d.action().name()))
                throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
              return true;
            })
        .defaultIfEmpty(false);
  }

  private Mono<Void> minimizeAliases(
      DatabaseClient connection, Disposition d, Map<UUID, Identity> prepared) {
    String column =
        d.action() == DispositionAction.MINIMIZE_TENANT ? "bound_tenant_id" : "first_user_id";
    return connection
        .sql("SELECT * FROM taska.free_eligibility_ledger WHERE " + column + "=:subject")
        .bind("subject", d.subjectId())
        .fetch()
        .all()
        .collectList()
        .flatMap(
            rows -> {
              Set<UUID> actual = new HashSet<>();
              for (var r : rows)
                if (r.get("first_user_id") != null) actual.add((UUID) r.get("first_user_id"));
              if (!prepared.keySet().equals(actual))
                throw fail(DomainStatus.ABORTED, "VERSION_CONFLICT");
              return Flux.fromIterable(prepared.values())
                  .concatMap(
                      identity ->
                          keysPresent(connection, identity)
                              .then(matches(connection, identity))
                              .flatMap(
                                  all ->
                                      alias(
                                          connection,
                                          identity,
                                          all,
                                          all.stream()
                                              .filter(
                                                  r ->
                                                      identity
                                                          .actor()
                                                          .equals(r.get("first_user_id")))
                                              .toList())))
                  .then();
            });
  }

  private Mono<Void> apply(DatabaseClient connection, Disposition d, Map<UUID, Identity> prepared) {
    if (d.action() == DispositionAction.MINIMIZE_TENANT
        || d.action() == DispositionAction.MINIMIZE_ACCOUNT) {
      return minimizeAliases(connection, d, prepared)
          .then(
              connection
                  .sql(
                      d.action() == DispositionAction.MINIMIZE_TENANT
                          ? "UPDATE taska.free_eligibility_ledger SET"
                              + " bound_tenant_id=NULL,first_user_id=NULL,operation_id=NULL WHERE"
                              + " bound_tenant_id=:subject"
                          : "UPDATE taska.free_eligibility_ledger SET first_user_id=NULL WHERE"
                              + " first_user_id=:subject")
                  .bind("subject", d.subjectId())
                  .fetch()
                  .rowsUpdated())
          .then();
    }
    return Flux.fromIterable(d.markers())
        .concatMap(
            key -> {
              var q =
                  connection
                      .sql(
                          d.action() == DispositionAction.ERASE
                              ? "DELETE FROM taska.free_eligibility_ledger WHERE"
                                  + " digest_key_version=:version AND email_digest=:digest"
                              : "UPDATE taska.free_eligibility_ledger SET"
                                  + " purpose_version=:purpose,review_due_at=:due WHERE"
                                  + " digest_key_version=:version AND email_digest=:digest")
                      .bind("version", key.keyVersion())
                      .bind("digest", key.digest());
              if (d.action() == DispositionAction.REVIEW)
                q =
                    q.bind("purpose", d.purposeVersion())
                        .bind("due", d.reviewDueAt().atOffset(ZoneOffset.UTC));
              return q.fetch()
                  .rowsUpdated()
                  .doOnNext(
                      n -> {
                        if (n != 1 && d.action() == DispositionAction.REVIEW)
                          throw fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
                      });
            })
        .then();
  }

  public Mono<FreeDispositionResult> disposition(String caseId) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .flatMap(
            v -> {
              String issuer = RpcContextBridge.details(v).getContext().getIssuerService();
              if (!Set.of("tenant-service", "auth-service").contains(issuer))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return authority("applyFreeEligibilityDisposition", issuer, null)
                  .flatMap(
                      caller ->
                          Mono.defer(
                                  () ->
                                      dispositions.resolve(
                                          caller, TenantRegistryService.uuid(caseId)))
                              .onErrorMap(FreeEligibilityLedger::dependency)
                              .switchIfEmpty(Mono.error(policy()))
                              .flatMap(
                                  d -> {
                                    if (!d.caseId().toString().equals(caseId)
                                        || d.decisionVersion() == null
                                        || !d.decisionVersion().matches("[A-Za-z0-9._:-]{1,120}")
                                        || d.action() == null) throw policy();
                                    if (issuer.equals("auth-service")
                                        && d.action() != DispositionAction.MINIMIZE_ACCOUNT)
                                      throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
                                    if (d.action() == DispositionAction.MINIMIZE_ACCOUNT
                                        || d.action() == DispositionAction.MINIMIZE_TENANT) {
                                      if (d.subjectId() == null
                                          || !d.markers().isEmpty()
                                          || d.identities().size() > 1000) throw policy();
                                      if (d.action() == DispositionAction.MINIMIZE_ACCOUNT
                                          && d.identities().stream()
                                              .anyMatch(i -> !d.subjectId().equals(i.userId())))
                                        throw policy();
                                    } else {
                                      if (d.markers().isEmpty()
                                          || d.markers().size() > 1000
                                          || !d.identities().isEmpty()
                                          || d.markers().stream()
                                              .anyMatch(
                                                  k ->
                                                      k.keyVersion() < 1
                                                          || k.digest().length != 32))
                                        throw policy();
                                      if (d.action() == DispositionAction.REVIEW
                                          && (d.purposeVersion() == null
                                              || !d.purposeVersion()
                                                  .matches("[A-Za-z0-9._:-]{1,120}")
                                              || d.reviewDueAt() == null
                                              || !d.reviewDueAt().isAfter(now())
                                              || d.reviewDueAt().isAfter(yearAfter(now()))
                                              || d.reviewDueAt().getNano() % 1000 != 0))
                                        throw policy();
                                    }
                                    return receipt(db, d)
                                        .flatMap(
                                            done -> {
                                              if (done)
                                                return Mono.just(
                                                    FreeDispositionResult.newBuilder()
                                                        .setApplied(true)
                                                        .build());
                                              Set<UUID> subjects = new HashSet<>();
                                              if (d.identities().stream()
                                                  .anyMatch(i -> !subjects.add(i.userId())))
                                                throw policy();
                                              return Flux.fromIterable(d.identities())
                                                  .concatMap(i -> fingerprint(i.userId(), i))
                                                  .collectMap(Identity::actor)
                                                  .flatMap(
                                                      prepared ->
                                                          tx.transactional(
                                                              locked(
                                                                  db,
                                                                  () ->
                                                                      receipt(db, d)
                                                                          .flatMap(
                                                                              replayed -> {
                                                                                if (replayed)
                                                                                  return Mono.just(
                                                                                      FreeDispositionResult
                                                                                          .newBuilder()
                                                                                          .setApplied(
                                                                                              true)
                                                                                          .build());
                                                                                return db.sql(
                                                                                        "SELECT"
                                                                                            + " set_config('taska.free_disposition',:action,true)")
                                                                                    .bind(
                                                                                        "action",
                                                                                        d.action()
                                                                                            .name())
                                                                                    .fetch()
                                                                                    .all()
                                                                                    .then(
                                                                                        apply(
                                                                                            db, d,
                                                                                            prepared))
                                                                                    .then(
                                                                                        db.sql(
                                                                                                "INSERT"
                                                                                                    + " INTO"
                                                                                                    + " taska.free_eligibility_dispositions(case_id,decision_version,action)"
                                                                                                    + " VALUES(:id,:version,:action)")
                                                                                            .bind(
                                                                                                "id",
                                                                                                d
                                                                                                    .caseId())
                                                                                            .bind(
                                                                                                "version",
                                                                                                d
                                                                                                    .decisionVersion())
                                                                                            .bind(
                                                                                                "action",
                                                                                                d.action()
                                                                                                    .name())
                                                                                            .fetch()
                                                                                            .rowsUpdated())
                                                                                    .thenReturn(
                                                                                        FreeDispositionResult
                                                                                            .newBuilder()
                                                                                            .setApplied(
                                                                                                true)
                                                                                            .build());
                                                                              }))));
                                            });
                                  }));
            });
  }
}
