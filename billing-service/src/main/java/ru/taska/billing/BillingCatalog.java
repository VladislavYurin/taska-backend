package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.CreatePlanVersionRequest;
import ru.taska.api.b2b.v1.CreatePlanVersionRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListBillingPlansRpcRequest;
import ru.taska.api.b2b.v1.Money;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.PlanVersionPage;
import ru.taska.api.b2b.v1.PublishPlanVersionRpcRequest;
import ru.taska.api.b2b.v1.QuotaLimit;
import ru.taska.api.b2b.v1.ResolvePublishedPlanRpcRequest;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.json.JsonMapper;

/** Global catalog only; tenant billing state never shares a nullable-tenant authorization path. */
public final class BillingCatalog {
  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final CatalogCursor cursors;
  private final JsonMapper json = JsonMapper.builder().build();

  public BillingCatalog(DatabaseClient db, TransactionalOperator tx, CatalogCursor cursors) {
    this.db = db;
    this.tx = tx;
    this.cursors = cursors;
  }

  private Mono<VerifiedTenantContext> authority(String action, boolean mutation) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!action.equals(v.action()))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              if (mutation) {
                boolean web =
                    c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                        && c.hasSessionId()
                        && !c.getSessionId().isBlank();
                boolean integration =
                    (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
                            || c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
                        && c.hasGrantId()
                        && !c.getGrantId().isBlank()
                        && c.hasGrantGeneration()
                        && !c.getGrantGeneration().isBlank()
                        && c.hasCredentialId()
                        && !c.getCredentialId().isBlank();
                // The required online Authority also verifies current GLOBAL_ADMIN/MFA or current
                // admin grant.
                if (v.actorId() == null
                    || v.tenantId() != null
                    || (!web && !integration)
                    || c.getActorGeneration().isBlank()
                    || !v.scopes().contains("write")
                    || c.getAdministrativeScope().getKind() != AdminScope.KindValue.KIND_PLATFORM
                    || c.getAdministrativeLevel() != AdminGrant.LevelValue.LEVEL_MODIFY)
                  throw fail(DomainStatus.PERMISSION_DENIED, "ADMIN_SCOPE_DENIED");
              } else if (!v.scopes().contains("read"))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return v;
            });
  }

  public Mono<PlanVersionPage> list(ListBillingPlansRpcRequest request) {
    return authority("listBillingPlans", false)
        .flatMap(
            v -> {
              int limit = request.getPage().getLimit() == 0 ? 50 : request.getPage().getLimit();
              if (limit < 1 || limit > 100) throw invalid();
              cursors.configured();
              String after =
                  request.getPage().hasCursor()
                      ? cursors.decode(request.getPage().getCursor(), limit)
                      : "";
              return db.sql(
                      "SELECT p.*,c.kind FROM taska.catalog_plans c JOIN LATERAL (SELECT * FROM"
                          + " taska.catalog_plan_versions p WHERE p.plan_code=c.plan_code AND"
                          + " p.published_at IS NOT NULL ORDER BY p.version DESC LIMIT 1) p ON true"
                          + " WHERE c.archived_at IS NULL AND c.plan_code>:after ORDER BY"
                          + " c.plan_code LIMIT :limit")
                  .bind("after", after)
                  .bind("limit", limit + 1)
                  .fetch()
                  .all()
                  .collectList()
                  .map(
                      rows -> {
                        var page = PlanVersionPage.newBuilder();
                        for (var row : rows.subList(0, Math.min(rows.size(), limit)))
                          page.addItems(map(row));
                        if (rows.size() > limit)
                          page.setNextCursor(
                              cursors.encode(
                                  rows.get(limit - 1).get("plan_code").toString(), limit));
                        return page.build();
                      });
            });
  }

  public Mono<PlanVersion> resolve(ResolvePublishedPlanRpcRequest request) {
    return authority("resolvePublishedPlan", false)
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !Set.of("tenant-service", "api-gateway", "project-service").contains(c.getIssuerService())
                  || !v.scopes().contains("catalog:resolve"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              UUID id;
              try {
                id = UUID.fromString(request.getPlanVersionId());
                if (!id.toString().equals(request.getPlanVersionId())) throw invalid();
              } catch (IllegalArgumentException e) {
                throw invalid();
              }
              return db.sql(
                      "SELECT p.*,c.kind FROM taska.catalog_plan_versions p JOIN"
                          + " taska.catalog_plans c USING(plan_code) WHERE p.id=:id AND"
                          + " p.published_at IS NOT NULL AND c.archived_at IS NULL AND NOT EXISTS"
                          + " (SELECT 1 FROM taska.catalog_plan_versions newer WHERE"
                          + " newer.plan_code=p.plan_code AND newer.published_at IS NOT NULL"
                          + " AND newer.version>p.version)")
                  .bind("id", id)
                  .fetch()
                  .one()
                  .map(this::map)
                  .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
            });
  }

  public Mono<PlanVersion> referenced(ResolvePublishedPlanRpcRequest request) {
    return authority("readReferencedPlanVersion", false)
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || v.tenantId() != null
                  || !Set.of("tenant-service", "api-gateway", "project-service").contains(c.getIssuerService())
                  || !c.hasServicePurpose()
                  || !"billing".equals(c.getServicePurpose())
                  || !v.scopes().contains("catalog:read"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              UUID id;
              try {
                id = UUID.fromString(request.getPlanVersionId());
                if (!id.toString().equals(request.getPlanVersionId()))
                  throw new IllegalArgumentException();
              } catch (IllegalArgumentException bad) {
                return Mono.error(invalid());
              }
              return db.sql(
                      "SELECT p.*,c.kind FROM taska.catalog_plan_versions p JOIN"
                          + " taska.catalog_plans c USING(plan_code) WHERE p.id=:id AND"
                          + " p.published_at IS NOT NULL")
                  .bind("id", id)
                  .fetch()
                  .one()
                  .map(this::map)
                  .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
            });
  }

  private static long number(String s) {
    try {
      if (!s.matches("0|[1-9][0-9]{0,18}")) throw invalid();
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private record Validated(long price, Map<QuotaLimit.KindValue, Long> limits, String features) {}

  private Validated validate(CreatePlanVersionRequest p) {
    if (!p.getCode().matches("[A-Z][A-Z0-9_]{0,63}")
        || p.getName().isBlank()
        || p.getName().length() > 120
        || !p.hasPrice()
        || p.getPrice().getCurrency() != Money.CurrencyValue.CURRENCY_EUR
        || p.getPrice().getExponent() != 2
        || p.getCapabilitiesCount() > 100
        || p.getLimitsCount() != 4) throw invalid();
    long price = number(p.getPrice().getAmountMinor());
    boolean free = p.getKind() == PlanVersion.KindValue.KIND_FREE,
        paid = p.getKind() == PlanVersion.KindValue.KIND_PAID;
    if ((!free && !paid)
        || (free
            && (price != 0 || p.getPeriod() != CreatePlanVersionRequest.PeriodValue.PERIOD_NONE))
        || (paid
            && (price == 0
                || p.getPeriod() != CreatePlanVersionRequest.PeriodValue.PERIOD_CALENDAR_MONTH)))
      throw invalid();
    Map<QuotaLimit.KindValue, Long> limits = new HashMap<>();
    for (var q : p.getLimitsList()) {
      if (q.getKind() == QuotaLimit.KindValue.KIND_UNSPECIFIED
          || q.getKind() == QuotaLimit.KindValue.UNRECOGNIZED
          || limits.containsKey(q.getKind())) throw invalid();
      if (q.getUnlimited()) {
        if (q.hasLimit() || q.getKind() != QuotaLimit.KindValue.KIND_TASK_LIFETIME || !paid)
          throw invalid();
        limits.put(q.getKind(), null);
      } else {
        if (!q.hasLimit()) throw invalid();
        long value = number(q.getLimit());
        if ((q.getKind() == QuotaLimit.KindValue.KIND_SEAT
                || q.getKind() == QuotaLimit.KindValue.KIND_PROJECT)
            && value == 0) throw invalid();
        limits.put(q.getKind(), value);
      }
    }
    if (new HashSet<>(p.getCapabilitiesList()).size() != p.getCapabilitiesCount()
        || p.getCapabilitiesList().stream().anyMatch(s -> !s.matches("[a-z][a-z0-9_.:-]{0,79}")))
      throw invalid();
    return new Validated(
        price, limits, json.writeValueAsString(p.getCapabilitiesList().stream().sorted().toList()));
  }

  private static byte[] hash(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Mono<PlanVersion> mutation(
      VerifiedTenantContext v,
      String op,
      MutationOptions opts,
      byte[] body,
      Supplier<Mono<PlanVersion>> work) {
    if (!opts.getIdempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
    UUID actor = UUID.fromString(v.actorId());
    byte[] digest = hash(body);
    // Same-intent concurrency serializes before reading its durable receipt; no in-memory cache.
    return tx.transactional(
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
            .bind("key", actor + ":" + op + ":" + opts.getIdempotencyKey())
            .fetch()
            .all()
            .then(
                db.sql(
                        "SELECT request_hash,response FROM taska.catalog_mutation_receipts WHERE"
                            + " actor_id=:actor AND operation=:op AND key=:key")
                    .bind("actor", actor)
                    .bind("op", op)
                    .bind("key", opts.getIdempotencyKey())
                    .map(
                        (row, metadata) ->
                            Map.of(
                                "request_hash",
                                row.get("request_hash", byte[].class),
                                "response",
                                row.get("response", String.class)))
                    .one()
                    .flatMap(
                        row -> {
                          if (!MessageDigest.isEqual(digest, (byte[]) row.get("request_hash")))
                            return Mono.error(
                                fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                          try {
                            return Mono.just(
                                PlanVersion.parseFrom(
                                    Base64.getDecoder().decode(row.get("response").toString())));
                          } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                            return Mono.error(new IllegalStateException("Invalid catalog receipt"));
                          }
                        })
                    .switchIfEmpty(
                        Mono.defer(work)
                            .flatMap(
                                result ->
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.catalog_mutation_receipts(actor_id,operation,key,request_hash,response)"
                                                + " VALUES(:actor,:op,:key,:hash,:response)")
                                        .bind("actor", actor)
                                        .bind("op", op)
                                        .bind("key", opts.getIdempotencyKey())
                                        .bind("hash", digest)
                                        .bind(
                                            "response",
                                            Base64.getEncoder()
                                                .encodeToString(result.toByteArray()))
                                        .fetch()
                                        .rowsUpdated()
                                        .thenReturn(result)))));
  }

  public Mono<PlanVersion> create(CreatePlanVersionRpcRequest request) {
    return authority("createPlanVersion", true)
        .flatMap(
            v -> {
              var p = request.getPayload();
              var valid = validate(p);
              if (request.getMutation().hasExpectedVersion()) throw invalid();
              return mutation(
                  v,
                  "createPlanVersion",
                  request.getMutation(),
                  p.toByteArray(),
                  () ->
                      db.sql(
                              "INSERT INTO taska.catalog_plans(plan_code,kind) VALUES(:code,:kind)"
                                  + " ON CONFLICT DO NOTHING")
                          .bind("code", p.getCode())
                          .bind(
                              "kind",
                              p.getKind() == PlanVersion.KindValue.KIND_FREE ? "FREE" : "PAID")
                          .fetch()
                          .rowsUpdated()
                          .then(
                              db.sql(
                                      "SELECT kind FROM taska.catalog_plans WHERE plan_code=:code"
                                          + " FOR UPDATE")
                                  .bind("code", p.getCode())
                                  .fetch()
                                  .one())
                          .flatMap(
                              row -> {
                                if (!row.get("kind")
                                    .equals(
                                        p.getKind() == PlanVersion.KindValue.KIND_FREE
                                            ? "FREE"
                                            : "PAID")) return Mono.error(invalid());
                                var sql =
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.catalog_plan_versions(id,plan_code,version,name,price_minor,currency,currency_exponent,period,max_projects,max_members,max_storage_bytes,max_tasks_per_project,features)"
                                                + " SELECT"
                                                + " :id,:code,COALESCE(MAX(version),0)+1,:name,:price,'EUR',2,:period,:projects,:members,:storage,:tasks,CAST(:features"
                                                + " AS jsonb) FROM taska.catalog_plan_versions"
                                                + " WHERE plan_code=:code RETURNING *")
                                        .bind("id", UUID.randomUUID())
                                        .bind("code", p.getCode())
                                        .bind("name", p.getName())
                                        .bind("price", valid.price())
                                        .bind(
                                            "period",
                                            p.getPeriod()
                                                    == CreatePlanVersionRequest.PeriodValue
                                                        .PERIOD_NONE
                                                ? "NONE"
                                                : "CALENDAR_MONTH")
                                        .bind(
                                            "projects",
                                            valid.limits().get(QuotaLimit.KindValue.KIND_PROJECT))
                                        .bind(
                                            "members",
                                            valid.limits().get(QuotaLimit.KindValue.KIND_SEAT))
                                        .bind(
                                            "storage",
                                            valid
                                                .limits()
                                                .get(QuotaLimit.KindValue.KIND_STORAGE_BYTES))
                                        .bind("features", valid.features());
                                Long tasks =
                                    valid.limits().get(QuotaLimit.KindValue.KIND_TASK_LIFETIME);
                                sql =
                                    tasks == null
                                        ? sql.bindNull("tasks", Long.class)
                                        : sql.bind("tasks", tasks);
                                return sql.fetch()
                                    .one()
                                    .map(
                                        r -> {
                                          r = new HashMap<>(r);
                                          r.put("kind", row.get("kind"));
                                          return map(r);
                                        })
                                    .flatMap(
                                        result ->
                                            event(v, result, "PlanVersionCreated")
                                                .thenReturn(result));
                              }));
            });
  }

  public Mono<PlanVersion> publish(PublishPlanVersionRpcRequest request) {
    return authority("publishPlanVersion", true)
        .flatMap(
            v -> {
              if (!request.getPayload().getConfirmed())
                throw fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED");
              UUID id;
              try {
                id = UUID.fromString(request.getPlanVersionId());
                if (!id.toString().equals(request.getPlanVersionId())) throw invalid();
              } catch (IllegalArgumentException e) {
                throw invalid();
              }
              if (!request.getMutation().hasExpectedVersion()) throw invalid();
              number(request.getMutation().getExpectedVersion());
              byte[] body =
                  (request.getPlanVersionId()
                          + ":"
                          + request.getMutation().getExpectedVersion()
                          + ":true")
                      .getBytes(StandardCharsets.UTF_8);
              return mutation(
                  v,
                  "publishPlanVersion",
                  request.getMutation(),
                  body,
                  () ->
                      db.sql(
                              "SELECT p.*,c.kind FROM taska.catalog_plan_versions p JOIN"
                                  + " taska.catalog_plans c USING(plan_code) WHERE p.id=:id FOR"
                                  + " UPDATE OF p")
                          .bind("id", id)
                          .fetch()
                          .one()
                          .switchIfEmpty(
                              Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                          .flatMap(
                              row -> {
                                if (!row.get("version")
                                    .toString()
                                    .equals(request.getMutation().getExpectedVersion()))
                                  return Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
                                if (row.get("published_at") != null)
                                  return Mono.error(
                                      fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
                                return db.sql(
                                        "UPDATE taska.catalog_plan_versions SET published_at=now()"
                                            + " WHERE id=:id RETURNING *")
                                    .bind("id", id)
                                    .fetch()
                                    .one()
                                    .map(
                                        r -> {
                                          r = new HashMap<>(r);
                                          r.put("kind", row.get("kind"));
                                          return map(r);
                                        })
                                    .flatMap(
                                        result ->
                                            event(v, result, "PlanVersionPublished")
                                                .thenReturn(result));
                              }));
            });
  }

  private Mono<Void> event(VerifiedTenantContext v, PlanVersion p, String type) {
    String payload =
        json.writeValueAsString(
            Map.of(
                "planVersionId",
                p.getId(),
                "planCode",
                p.getCode(),
                "version",
                p.getVersion(),
                "actorId",
                v.actorId()));
    return db.sql(
            "INSERT INTO"
                + " taska.global_outbox_events(id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,status,created_at)"
                + " VALUES(:id,'PlanVersion',:aggregate,:version,:type,1,CAST(:payload AS"
                + " jsonb),'NEW',now())")
        .bind("id", UUID.randomUUID())
        .bind("aggregate", p.getId())
        .bind("version", Long.parseLong(p.getVersion()))
        .bind("type", type)
        .bind("payload", payload)
        .fetch()
        .rowsUpdated()
        .then();
  }

  PlanVersion map(Map<String, Object> row) {
    var p =
        PlanVersion.newBuilder()
            .setId(row.get("id").toString())
            .setCode(row.get("plan_code").toString())
            .setVersion(row.get("version").toString())
            .setName(row.get("name").toString())
            .setKind(PlanVersion.KindValue.valueOf("KIND_" + row.get("kind")))
            .setPrice(
                Money.newBuilder()
                    .setAmountMinor(row.get("price_minor").toString())
                    .setCurrency(Money.CurrencyValue.CURRENCY_EUR)
                    .setExponent(2))
            .setPeriod(PlanVersion.PeriodValue.valueOf("PERIOD_" + row.get("period")))
            .setState(
                row.get("published_at") == null
                    ? PlanVersion.StateValue.STATE_DRAFT
                    : PlanVersion.StateValue.STATE_PUBLISHED);
    if (row.get("published_at") != null)
      p.setPublishedAt(((OffsetDateTime) row.get("published_at")).toInstant().toString());
    for (var entry :
        Map.of(
                QuotaLimit.KindValue.KIND_PROJECT,
                "max_projects",
                QuotaLimit.KindValue.KIND_SEAT,
                "max_members",
                QuotaLimit.KindValue.KIND_STORAGE_BYTES,
                "max_storage_bytes",
                QuotaLimit.KindValue.KIND_TASK_LIFETIME,
                "max_tasks_per_project")
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .toList()) {
      var q = QuotaLimit.newBuilder().setKind(entry.getKey());
      Object value = row.get(entry.getValue());
      if (value == null) q.setUnlimited(true);
      else q.setLimit(value.toString());
      p.addLimits(q);
    }
    String features =
        row.get("features") instanceof io.r2dbc.postgresql.codec.Json j
            ? j.asString()
            : row.get("features").toString();
    p.addAllCapabilities(Arrays.asList(json.readValue(features, String[].class)));
    return p.build();
  }
}
