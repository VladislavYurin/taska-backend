package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.GetSubscriptionRpcRequest;
import ru.taska.api.b2b.v1.ListSubscriptionPeriodsRpcRequest;
import ru.taska.api.b2b.v1.Subscription;
import ru.taska.api.b2b.v1.SubscriptionPeriod;
import ru.taska.api.b2b.v1.SubscriptionPeriodPage;
import ru.taska.billing.SubscriptionDependencies.CoverageEvidence;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.billing.SubscriptionDependencies.SettlementEvidenceAuthority;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.json.JsonMapper;

/** Tenant-scoped model, not a payment processor. Settlement must join this same transaction. */
public final class SubscriptionStore {
  private final TenantTransactionExecutor transactions;
  private final FinancialReadAuthority readers;
  private final SettlementEvidenceAuthority evidence;
  private final Clock clock;
  private final PeriodCursor cursor;
  private final JsonMapper json = JsonMapper.builder().build();

  public SubscriptionStore(
      TenantTransactionExecutor transactions,
      FinancialReadAuthority readers,
      SettlementEvidenceAuthority evidence,
      Clock clock,
      PeriodCursor cursor) {
    this.transactions = transactions;
    this.readers = readers;
    this.evidence = evidence;
    this.clock = clock;
    this.cursor = cursor;
  }

  private static UUID uuid(String s) {
    try {
      UUID id = UUID.fromString(s);
      if (!id.toString().equals(s)) throw invalid();
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private Mono<VerifiedTenantContext> context(UUID tenant, String action) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              if (!tenant.toString().equals(v.tenantId()) || !action.equals(v.action()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              return v;
            });
  }

  private Mono<VerifiedTenantContext> service(UUID tenant, String action, String issuer) {
    return context(tenant, action)
        .map(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !issuer.equals(c.getIssuerService())
                  || !v.scopes().contains("write"))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return v;
            });
  }

  private Mono<VerifiedTenantContext> read(UUID tenant, String action) {
    return new FinancialAccess(readers).authorize(tenant, action, false);
  }

  private static byte[] digest(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Mono<Subscription> command(
      VerifiedTenantContext v,
      UUID tenant,
      String op,
      String key,
      String input,
      Function<DatabaseClient, Mono<Subscription>> work) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
    byte[] hash = digest(input);
    return transactions.execute(
        db -> {
          Mono<Subscription> previous =
              db.sql(
                      "SELECT request_hash,response FROM taska.subscription_command_receipts WHERE"
                          + " tenant_id=:tenant AND operation_code=:op AND key=:key")
                  .bind("tenant", tenant)
                  .bind("op", op)
                  .bind("key", key)
                  .map(
                      (r, m) ->
                          Map.of(
                              "hash",
                              r.get("request_hash", byte[].class),
                              "response",
                              r.get("response", String.class)))
                  .one()
                  .flatMap(
                      row -> {
                        if (!MessageDigest.isEqual(hash, (byte[]) row.get("hash")))
                          return Mono.error(
                              fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                        try {
                          return Mono.just(
                              Subscription.parseFrom(
                                  Base64.getDecoder().decode(row.get("response").toString())));
                        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                          return Mono.error(
                              new IllegalStateException("Invalid subscription receipt"));
                        }
                      });
          return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))")
              .bind("lock", tenant + ":" + op + ":" + key)
              .fetch()
              .all()
              .then(
                  previous.switchIfEmpty(
                      Mono.defer(() -> work.apply(db))
                          .flatMap(
                              result -> {
                                var insert =
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.subscription_command_receipts(tenant_id,operation_code,key,request_hash,response,issuer_service,actor_id)"
                                                + " VALUES(:tenant,:op,:key,:hash,:response,:issuer,:actor)")
                                        .bind("tenant", tenant)
                                        .bind("op", op)
                                        .bind("key", key)
                                        .bind("hash", hash)
                                        .bind(
                                            "response",
                                            Base64.getEncoder()
                                                .encodeToString(result.toByteArray()))
                                        .bind(
                                            "issuer",
                                            RpcContextBridge.details(v)
                                                .getContext()
                                                .getIssuerService());
                                insert =
                                    v.actorId() == null
                                        ? insert.bindNull("actor", UUID.class)
                                        : insert.bind("actor", uuid(v.actorId()));
                                return insert.fetch().rowsUpdated().thenReturn(result);
                              })));
        });
  }

  private Mono<Map<String, Object>> plan(DatabaseClient db, UUID id, boolean current) {
    return db.sql(
            "SELECT p.id,c.kind,(to_jsonb(p)||jsonb_build_object('kind',c.kind))::text AS snapshot"
                + " FROM taska.catalog_plan_versions p JOIN taska.catalog_plans c USING(plan_code)"
                + " WHERE p.id=:id AND p.published_at IS NOT NULL"
                + (current
                    ? " AND c.archived_at IS NULL AND NOT EXISTS(SELECT 1 FROM"
                        + " taska.catalog_plan_versions newer WHERE newer.plan_code=p.plan_code"
                        + " AND newer.published_at IS NOT NULL AND newer.version>p.version)"
                    : ""))
        .bind("id", id)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  /** Provisioning only. Selecting PAID cannot create a free fallback or paid period. */
  public Mono<Subscription> initialize(UUID tenant, UUID planVersionId, String key) {
    return service(tenant, "initializeSubscription", "tenant-service")
        .flatMap(
            v ->
                command(
                    v,
                    tenant,
                    "initializeSubscription",
                    key,
                    planVersionId.toString(),
                    db ->
                        plan(db, planVersionId, true)
                            .flatMap(
                                plan ->
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode)"
                                                + " VALUES(:tenant,:plan,CAST(:snapshot AS"
                                                + " jsonb),:mode) ON CONFLICT DO NOTHING")
                                        .bind("tenant", tenant)
                                        .bind("plan", planVersionId)
                                        .bind("snapshot", plan.get("snapshot"))
                                        .bind(
                                            "mode",
                                            plan.get("kind").equals("FREE")
                                                ? "FREE"
                                                : "AWAITING_PAYMENT")
                                        .fetch()
                                        .rowsUpdated()
                                        .flatMap(
                                            n ->
                                                lock(db, tenant)
                                                    .flatMap(
                                                        row -> {
                                                          if (!row.get("selected_plan_version_id")
                                                              .equals(planVersionId))
                                                            return Mono.error(
                                                                fail(
                                                                    DomainStatus
                                                                        .FAILED_PRECONDITION,
                                                                    "INVALID_STATE"));
                                                          return n == 0
                                                              ? view(db, tenant)
                                                              : event(
                                                                      db,
                                                                      tenant,
                                                                      1,
                                                                      "SubscriptionInitialized",
                                                                      Map.of(
                                                                          "planVersionId",
                                                                          planVersionId.toString()))
                                                                  .then(view(db, tenant));
                                                        })))));
  }

  private Mono<Map<String, Object>> lock(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT * FROM taska.subscriptions WHERE tenant_id=:tenant FOR UPDATE")
        .bind("tenant", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private void validate(UUID tenant, CoverageEvidence e) {
    if (e == null
        || !tenant.equals(e.tenantId())
        || e.invoiceId() == null
        || e.financialFactId() == null
        || e.planVersionId() == null
        || e.activationOperationId() == null
        || e.startsAt() == null
        || e.endsAt() == null
        || !e.endsAt().isAfter(e.startsAt())
        || e.anchorDay() < 1
        || e.anchorDay() > 31
        || e.anchorTimeUtc() == null
        || e.confirmedAt() == null
        || e.source() == null
        || e.startsAt().getNano() % 1000 != 0
        || e.endsAt().getNano() % 1000 != 0
        || e.confirmedAt().getNano() % 1000 != 0
        || e.anchorTimeUtc().getNano() % 1000 != 0
        || new java.util.HashSet<>(e.supersededPeriodIds()).size()
            != e.supersededPeriodIds().size()) throw invalid();
  }

  /** Invoked by confirmed settlement inside its transaction, never exposed as a browser RPC. */
  public Mono<Subscription> grant(UUID tenant, UUID evidenceId, String key) {
    return service(tenant, "applySubscriptionCoverage", "billing-service")
        .flatMap(
            v ->
                command(
                    v,
                    tenant,
                    "applySubscriptionCoverage",
                    key,
                    evidenceId.toString(),
                    db ->
                        lock(db, tenant)
                            .then(
                                evidence
                                    .confirmed(db, tenant, evidenceId)
                                    .switchIfEmpty(
                                        Mono.error(
                                            fail(
                                                DomainStatus.FAILED_PRECONDITION,
                                                "PAYMENT_PENDING"))))
                            .flatMap(e -> grantConfirmed(db, tenant, e))));
  }

  private Mono<Subscription> grantConfirmed(DatabaseClient db, UUID tenant, CoverageEvidence e) {
    validate(tenant, e);
    if (!e.supersededPeriodIds().isEmpty() && e.startsAt().isAfter(clock.instant()))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
    return db.sql(
            "SELECT * FROM taska.subscription_periods WHERE tenant_id=:tenant AND"
                + " (invoice_id=:invoice OR activation_operation_id=:operation)")
        .bind("tenant", tenant)
        .bind("invoice", e.invoiceId())
        .bind("operation", e.activationOperationId())
        .fetch()
        .all()
        .collectList()
        .flatMap(
            existing -> {
              if (!existing.isEmpty()) {
                if (existing.size() != 1 || !same(existing.getFirst(), e))
                  return Mono.error(fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                return view(db, tenant);
              }
              return plan(db, e.planVersionId(), false)
                  .flatMap(
                      plan -> {
                        if (!plan.get("kind").equals("PAID"))
                          return Mono.error(
                              fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
                        return supersede(db, tenant, e.supersededPeriodIds())
                            .then(insertPeriod(db, tenant, e, plan.get("snapshot").toString()))
                            .then(
                                db.sql(
                                        "UPDATE taska.subscriptions SET"
                                            + " mode='PAID',selected_plan_version_id=CASE WHEN"
                                            + " :starts<=:now THEN :plan ELSE"
                                            + " selected_plan_version_id"
                                            + " END,selected_terms_snapshot=CASE WHEN :starts<=:now"
                                            + " THEN CAST(:snapshot AS jsonb) ELSE"
                                            + " selected_terms_snapshot END,current_period_id=CASE"
                                            + " WHEN :starts<=:now THEN :period ELSE"
                                            + " current_period_id"
                                            + " END,anchor_day=:day,anchor_time_utc=:time,version=version+1,updated_at=now()"
                                            + " WHERE tenant_id=:tenant RETURNING version")
                                    .bind("tenant", tenant)
                                    .bind("starts", e.startsAt().atOffset(ZoneOffset.UTC))
                                    .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
                                    .bind("plan", e.planVersionId())
                                    .bind("snapshot", plan.get("snapshot"))
                                    .bind("period", e.activationOperationId())
                                    .bind("day", e.anchorDay())
                                    .bind("time", e.anchorTimeUtc())
                                    .map((r, m) -> r.get("version", Long.class))
                                    .one())
                            .flatMap(
                                version ->
                                    event(
                                            db,
                                            tenant,
                                            version,
                                            "SubscriptionCoverageGranted",
                                            Map.of(
                                                "periodId",
                                                e.activationOperationId().toString(),
                                                "invoiceId",
                                                e.invoiceId().toString(),
                                                "financialFactId",
                                                e.financialFactId().toString()))
                                        .then(view(db, tenant)));
                      });
            });
  }

  private boolean same(Map<String, Object> r, CoverageEvidence e) {
    return r.get("invoice_id").equals(e.invoiceId())
        && r.get("source_financial_fact_id").equals(e.financialFactId())
        && r.get("plan_version_id").equals(e.planVersionId())
        && r.get("activation_operation_id").equals(e.activationOperationId())
        && ((OffsetDateTime) r.get("starts_at")).toInstant().equals(e.startsAt())
        && ((OffsetDateTime) r.get("ends_at")).toInstant().equals(e.endsAt())
        && ((Number) r.get("anchor_day")).intValue() == e.anchorDay()
        && r.get("anchor_time_utc").equals(e.anchorTimeUtc())
        && r.get("payment_source").equals(e.source().name())
        && ((OffsetDateTime) r.get("confirmed_at")).toInstant().equals(e.confirmedAt());
  }

  private Mono<Void> supersede(DatabaseClient db, UUID tenant, List<UUID> ids) {
    return Flux.fromIterable(ids)
        .concatMap(
            id ->
                db.sql(
                        "UPDATE taska.subscription_periods SET status='SUPERSEDED' WHERE"
                            + " tenant_id=:tenant AND id=:id AND status='GRANTED'")
                    .bind("tenant", tenant)
                    .bind("id", id)
                    .fetch()
                    .rowsUpdated()
                    .flatMap(
                        n ->
                            n == 1
                                ? Mono.just(n)
                                : Mono.error(
                                    fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"))))
        .then();
  }

  private Mono<Void> insertPeriod(
      DatabaseClient db, UUID tenant, CoverageEvidence e, String snapshot) {
    return db.sql(
            "INSERT INTO"
                + " taska.subscription_periods(id,tenant_id,invoice_id,source_financial_fact_id,plan_version_id,starts_at,ends_at,anchor_day,anchor_time_utc,activation_operation_id,terms_snapshot,confirmed_at,payment_source)"
                + " VALUES(:id,:tenant,:invoice,:fact,:plan,:start,:end,:day,:time,:id,CAST(:snapshot"
                + " AS jsonb),:confirmed,:source)")
        .bind("id", e.activationOperationId())
        .bind("tenant", tenant)
        .bind("invoice", e.invoiceId())
        .bind("fact", e.financialFactId())
        .bind("plan", e.planVersionId())
        .bind("start", e.startsAt().atOffset(ZoneOffset.UTC))
        .bind("end", e.endsAt().atOffset(ZoneOffset.UTC))
        .bind("day", e.anchorDay())
        .bind("time", e.anchorTimeUtc())
        .bind("snapshot", snapshot)
        .bind("confirmed", e.confirmedAt().atOffset(ZoneOffset.UTC))
        .bind("source", e.source().name())
        .fetch()
        .rowsUpdated()
        .then();
  }

  /**
   * Only final verified reversals; D251 revokes whole linked coverage, never a proportional
   * remainder.
   */
  public Mono<Subscription> revoke(UUID tenant, UUID evidenceId, String key) {
    return service(tenant, "revokeSubscriptionCoverage", "billing-service")
        .flatMap(
            v ->
                command(
                    v,
                    tenant,
                    "revokeSubscriptionCoverage",
                    key,
                    evidenceId.toString(),
                    db ->
                        lock(db, tenant)
                            .then(
                                evidence
                                    .reversed(db, tenant, evidenceId)
                                    .switchIfEmpty(
                                        Mono.error(
                                            fail(
                                                DomainStatus.FAILED_PRECONDITION,
                                                "PAYMENT_PENDING"))))
                            .flatMap(
                                e -> {
                                  if (!tenant.equals(e.tenantId())
                                      || e.invoiceId() == null
                                      || e.financialFactId() == null) throw invalid();
                                  return db.sql(
                                          "UPDATE taska.subscription_periods SET status='REVOKED'"
                                              + " WHERE tenant_id=:tenant AND invoice_id=:invoice"
                                              + " AND source_financial_fact_id=:fact AND"
                                              + " status='GRANTED'")
                                      .bind("tenant", tenant)
                                      .bind("invoice", e.invoiceId())
                                      .bind("fact", e.financialFactId())
                                      .fetch()
                                      .rowsUpdated()
                                      .flatMap(
                                          n -> {
                                            if (n == 0) return view(db, tenant);
                                            return db.sql(
                                                    "UPDATE taska.subscriptions SET"
                                                        + " renewal_enabled=false,autopay_state='DISABLED',version=version+1,updated_at=now()"
                                                        + " WHERE tenant_id=:tenant RETURNING"
                                                        + " version")
                                                .bind("tenant", tenant)
                                                .map((r, m) -> r.get("version", Long.class))
                                                .one()
                                                .flatMap(
                                                    version ->
                                                        event(
                                                                db,
                                                                tenant,
                                                                version,
                                                                "SubscriptionCoverageRevoked",
                                                                Map.of(
                                                                    "invoiceId",
                                                                    e.invoiceId().toString(),
                                                                    "financialFactId",
                                                                    e.financialFactId().toString()))
                                                            .then(view(db, tenant)));
                                          });
                                })));
  }

  private Mono<Void> event(
      DatabaseClient db, UUID tenant, long version, String type, Map<String, String> facts) {
    String payload = json.writeValueAsString(facts);
    return db.sql(
            "INSERT INTO"
                + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                + " VALUES(:id,:tenant,'Subscription',:tenant,:version,:type,1,CAST(:payload AS"
                + " jsonb),:operation,'NEW',now())")
        .bind("id", UUID.randomUUID())
        .bind("tenant", tenant)
        .bind("version", version)
        .bind("type", type)
        .bind("payload", payload)
        .bind("operation", UUID.randomUUID())
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Subscription> view(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT s.*,p.id AS effective_period_id,p.plan_version_id AS"
                + " effective_plan_id,p.ends_at AS paid_through,p.anchor_day AS period_anchor FROM"
                + " taska.subscriptions s LEFT JOIN LATERAL (SELECT * FROM"
                + " taska.subscription_periods p WHERE p.tenant_id=s.tenant_id AND"
                + " p.status='GRANTED' AND p.starts_at<=:now ORDER BY (p.ends_at>:now) DESC,"
                + " p.starts_at DESC,p.id DESC"
                + " LIMIT 1) p ON true WHERE s.tenant_id=:tenant")
        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
        .bind("tenant", tenant)
        .fetch()
        .one()
        .map(
            row -> {
              var out =
                  Subscription.newBuilder()
                      .setTenantId(tenant.toString())
                      .setPlanVersionId(
                          (row.get("effective_plan_id") == null
                                  ? row.get("selected_plan_version_id")
                                  : row.get("effective_plan_id"))
                              .toString())
                      .setVersion(row.get("version").toString())
                      .setRenewalCancelled((Boolean) row.get("renewal_cancelled"))
                      .setAutopayState(
                          Subscription.AutopayStateValue.valueOf(
                              "AUTOPAY_STATE_" + row.get("autopay_state")));
              if (row.get("effective_period_id") != null)
                out.setCurrentPeriodId(row.get("effective_period_id").toString())
                    .setPaidThrough(
                        ((OffsetDateTime) row.get("paid_through")).toInstant().toString())
                    .setAnchorDay(((Number) row.get("period_anchor")).longValue());
              if (row.get("next_plan_version_id") != null)
                out.setScheduledPlanVersionId(row.get("next_plan_version_id").toString());
              if (row.get("next_effective_at") != null)
                out.setScheduledEffectiveAt(
                    ((OffsetDateTime) row.get("next_effective_at")).toInstant().toString());
              return out.build();
            })
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  public Mono<Subscription> get(GetSubscriptionRpcRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return read(tenant, "getSubscription")
        .flatMap(v -> transactions.execute(db -> view(db, tenant)));
  }

  private String binding(VerifiedTenantContext v, int limit) {
    var c = RpcContextBridge.details(v).getContext();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            digest(
                json.writeValueAsString(
                    List.of(
                        "subscriptionPeriods/v1",
                        v.tenantId(),
                        v.actorId(),
                        c.getActorGeneration(),
                        c.getMembershipGeneration(),
                        c.getCredentialKind().name(),
                        c.getSessionId(),
                        c.getCredentialId(),
                        c.getGrantId(),
                        c.getGrantGeneration(),
                        c.getAdministrativeScope().getKind().name(),
                        new TreeSet<>(c.getAdministrativeScope().getTenantIdsList()),
                        c.getAdministrativeLevel().name(),
                        new TreeSet<>(v.scopes()),
                        limit))));
  }

  public Mono<SubscriptionPeriodPage> periods(ListSubscriptionPeriodsRpcRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return read(tenant, "listSubscriptionPeriods")
        .flatMap(
            v -> {
              int limit = request.getPage().getLimit() == 0 ? 50 : request.getPage().getLimit();
              if (limit < 1 || limit > 100) throw invalid();
              cursor.requireConfigured();
              String binding = binding(v, limit);
              var pos =
                  request.getPage().hasCursor()
                      ? cursor.decode(request.getPage().getCursor(), binding)
                      : null;
              return transactions.execute(
                  db ->
                      db.sql(
                              "SELECT s.version AS subscription_version,p.* FROM"
                                  + " taska.subscriptions s LEFT JOIN LATERAL(SELECT * FROM"
                                  + " taska.subscription_periods p WHERE p.tenant_id=s.tenant_id"
                                  + " AND (:has_cursor=false OR s.version=:version) AND"
                                  + " (:has_cursor=false OR"
                                  + " (p.created_at,p.id)<(:after_at,:after_id)) ORDER BY"
                                  + " p.created_at DESC,p.id DESC LIMIT :fetch) p ON true WHERE"
                                  + " s.tenant_id=:tenant ORDER BY p.created_at DESC,p.id DESC")
                          .bind("tenant", tenant)
                          .bind("has_cursor", pos != null)
                          .bind("version", pos == null ? 0L : pos.generation())
                          .bind(
                              "after_at",
                              (pos == null ? Instant.EPOCH : pos.createdAt())
                                  .atOffset(ZoneOffset.UTC))
                          .bind("after_id", pos == null ? new UUID(0, 0) : pos.periodId())
                          .bind("fetch", limit + 1)
                          .fetch()
                          .all()
                          .collectList()
                          .map(
                              rows -> {
                                if (rows.isEmpty())
                                  throw fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
                                long version =
                                    ((Number) rows.getFirst().get("subscription_version"))
                                        .longValue();
                                if (pos != null && pos.generation() != version) throw invalid();
                                var records =
                                    rows.stream().filter(r -> r.get("id") != null).toList();
                                var out = SubscriptionPeriodPage.newBuilder();
                                for (var r : records.subList(0, Math.min(records.size(), limit)))
                                  out.addItems(period(r));
                                if (records.size() > limit) {
                                  var last = records.get(limit - 1);
                                  out.setNextCursor(
                                      cursor.encode(
                                          binding,
                                          version,
                                          ((OffsetDateTime) last.get("created_at")).toInstant(),
                                          (UUID) last.get("id")));
                                }
                                return out.build();
                              }));
            });
  }

  private static SubscriptionPeriod period(Map<String, Object> r) {
    return SubscriptionPeriod.newBuilder()
        .setId(r.get("id").toString())
        .setTenantId(r.get("tenant_id").toString())
        .setInvoiceId(r.get("invoice_id").toString())
        .setPlanVersionId(r.get("plan_version_id").toString())
        .setStartsAt(((OffsetDateTime) r.get("starts_at")).toInstant().toString())
        .setEndsAt(((OffsetDateTime) r.get("ends_at")).toInstant().toString())
        .setAnchorDay(((Number) r.get("anchor_day")).longValue())
        .setState(SubscriptionPeriod.StateValue.valueOf("STATE_" + r.get("status")))
        .setConfirmedAt(((OffsetDateTime) r.get("confirmed_at")).toInstant().toString())
        .setPaymentSource(
            SubscriptionPeriod.PaymentSourceValue.valueOf(
                "PAYMENT_SOURCE_" + r.get("payment_source")))
        .build();
  }
}
