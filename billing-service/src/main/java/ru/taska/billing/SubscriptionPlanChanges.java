package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.*;
import static ru.taska.billing.PlanChangeDependencies.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;
import tools.jackson.databind.json.JsonMapper;

/** Durable owner intent. Payment confirmation is a separate, bound financial fact (TAS-352). */
public final class SubscriptionPlanChanges {
  private final SubscriptionStore subscriptions;
  private final TenantTransactionExecutor tx;
  private final FinancialAccess access;
  private final Policy policy;
  private final FreeAuthority free;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public SubscriptionPlanChanges(
      SubscriptionStore subscriptions, Policy policy, FreeAuthority free, Clock clock) {
    this.subscriptions = Objects.requireNonNull(subscriptions);
    this.tx = subscriptions.transactions();
    this.access = new FinancialAccess(subscriptions.readers());
    this.policy = Objects.requireNonNull(policy);
    this.free = Objects.requireNonNull(free);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<PlanChange> change(ChangeSubscriptionPlanRpcRequest request) {
    UUID tenant = uuid(request.getTenantId()),
        target = uuid(request.getPayload().getPlanVersionId());
    long expected = expected(request.getMutation());
    String key = key(request.getMutation());
    if (!request.getPayload().getConfirmed())
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED"));
    return access
        .authorize(tenant, "changeSubscriptionPlan", true)
        .flatMap(
            c ->
                current(c, Command.CHANGE)
                    .flatMap(
                        grant -> {
                          byte[] hash =
                              hash(
                                  c.actorId()
                                      + "\n"
                                      + expected
                                      + "\n"
                                      + Base64.getEncoder()
                                          .encodeToString(request.getPayload().toByteArray()));
                          return tx.execute(
                                  db ->
                                      subscriptions
                                          .lock(db, tenant)
                                          .then(current(c, Command.CHANGE))
                                          .flatMap(
                                              lockedGrant ->
                                                  byKey(db, tenant, uuid(c.actorId()), key)
                                                      .flatMap(
                                                          previous -> {
                                                            if (!MessageDigest.isEqual(
                                                                hash,
                                                                storedHash(
                                                                    previous.get("request_hash"))))
                                                              return Mono.error(
                                                                  fail(
                                                                      DomainStatus.ALREADY_EXISTS,
                                                                      "IDEMPOTENCY_CONFLICT"));
                                                            return current(c, Command.REPLAY)
                                                                .thenReturn(previous);
                                                          })
                                                      .switchIfEmpty(
                                                          Mono.defer(
                                                              () ->
                                                                  prepare(
                                                                      db,
                                                                      c,
                                                                      lockedGrant,
                                                                      tenant,
                                                                      target,
                                                                      expected,
                                                                      key,
                                                                      hash,
                                                                      request.getPayload())))))
                              .flatMap(
                                  row ->
                                      row.get("response") != null
                                          ? Mono.just(response(row))
                                          : admitAndComplete(row));
                        }));
  }

  private record Choice(
      UUID target,
      UUID previous,
      String snapshot,
      PlanChange.EffectValue effect,
      boolean noOp,
      boolean free,
      String currency,
      String method) {}

  private Mono<Map<String, Object>> prepare(
      DatabaseClient db,
      VerifiedTenantContext c,
      Grant grant,
      UUID tenant,
      UUID target,
      long expected,
      String key,
      byte[] hash,
      ChangePlanRequest request) {
    return subscriptions
        .lock(db, tenant)
        .flatMap(
            before -> {
              version(before, expected);
              if ("AWAITING_PAYMENT".equals(before.get("mode")))
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "INITIAL_PAYMENT_REQUIRED"));
              return noPreparedFree(db, tenant)
                  .then(subscriptions.view(db, tenant))
                  .flatMap(
                      view ->
                          subscriptions
                              .plan(db, target, true)
                              .flatMap(
                                  plan ->
                                      subscriptions
                                          .plan(db, uuid(view.getPlanVersionId()), false)
                                          .map(
                                              old ->
                                                  choose(before, view, target, plan, old, request)))
                              .flatMap(
                                  choice ->
                                      accept(
                                          db, c, grant, before, tenant, key, hash, view, choice)));
            });
  }

  private Choice choose(
      Map<String, Object> s,
      Subscription current,
      UUID target,
      Map<String, Object> plan,
      Map<String, Object> old,
      ChangePlanRequest request) {
    var terms = json.readTree(plan.get("snapshot").toString());
    var previous = json.readTree(old.get("snapshot").toString());
    UUID previousId = uuid(current.getPlanVersionId());
    boolean same = target.equals(previousId), isFree = "FREE".equals(plan.get("kind"));
    if (!same && terms.get("plan_code").asText().equals(previous.get("plan_code").asText()))
      throw fail(DomainStatus.FAILED_PRECONDITION, "PLAN_TERMS_CHANGE_REQUIRES_NOTICE");
    boolean active =
        current.hasPaidThrough()
            && clock.instant().isBefore(Instant.parse(current.getPaidThrough()));
    boolean noOp = same && ("FREE".equals(s.get("mode")) || active);
    var effect =
        noOp || isFree
            ? PlanChange.EffectValue.EFFECT_IMMEDIATE
            : active && terms.get("price_minor").asLong() > previous.get("price_minor").asLong()
                ? PlanChange.EffectValue.EFFECT_NEXT_PERIOD
                : PlanChange.EffectValue.EFFECT_AFTER_PAYMENT;
    if (request.hasCurrency()
            && !Set.of(
                    ChangePlanRequest.CurrencyValue.CURRENCY_EUR,
                    ChangePlanRequest.CurrencyValue.CURRENCY_USD)
                .contains(request.getCurrency())
        || request.hasPaymentMethod()
            && !Set.of(
                    ChangePlanRequest.PaymentMethodValue.PAYMENT_METHOD_PSP,
                    ChangePlanRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE)
                .contains(request.getPaymentMethod())) throw invalid();
    if (effect != PlanChange.EffectValue.EFFECT_IMMEDIATE
        && (!request.hasCurrency() || !request.hasPaymentMethod())) throw invalid();
    return new Choice(
        target,
        previousId,
        plan.get("snapshot").toString(),
        effect,
        noOp,
        isFree,
        request.hasCurrency() ? request.getCurrency().name().substring(9) : null,
        request.hasPaymentMethod() ? request.getPaymentMethod().name().substring(15) : null);
  }

  private Mono<Optional<Instant>> boundary(DatabaseClient db, UUID tenant, Choice c) {
    if (c.effect() != PlanChange.EffectValue.EFFECT_NEXT_PERIOD) return Mono.just(Optional.empty());
    return db.sql(
            "SELECT max(ends_at) boundary FROM taska.subscription_periods WHERE tenant_id=:t AND"
                + " status='GRANTED' AND ends_at>:now")
        .bind("t", tenant)
        .bind("now", offset(clock.instant()))
        .map(
            (r, m) ->
                Optional.ofNullable(r.get("boundary", OffsetDateTime.class))
                    .map(OffsetDateTime::toInstant))
        .one();
  }

  private Mono<Map<String, Object>> accept(
      DatabaseClient db,
      VerifiedTenantContext actor,
      Grant grant,
      Map<String, Object> s,
      UUID tenant,
      String key,
      byte[] hash,
      Subscription view,
      Choice c) {
    boolean cancelPending = c.noOp() && s.get("active_plan_change_id") != null;
    if (c.noOp() && !cancelPending)
      return insert(
              db,
              actor,
              grant,
              s,
              tenant,
              c.target(),
              c.previous(),
              key,
              hash,
              c.snapshot(),
              "IMMEDIATE",
              "APPLIED",
              null,
              null,
              null,
              false)
          .flatMap(
              row ->
                  saveResponse(
                      db,
                      row,
                      view,
                      c.effect(),
                      ((OffsetDateTime) row.get("applied_at")).toInstant()));
    return boundary(db, tenant, c)
        .flatMap(
            at -> {
              boolean freeIntent = c.free() && !c.noOp();
              String state =
                  cancelPending
                      ? "APPLIED"
                      : freeIntent
                          ? "PREPARED"
                          : c.effect() == PlanChange.EffectValue.EFFECT_NEXT_PERIOD
                              ? "SCHEDULED"
                              : "AWAITING_PAYMENT";
              Mono<Void> supersede = freeIntent ? Mono.empty() : supersedePending(db, tenant);
              return supersede
                  .then(
                      insert(
                          db,
                          actor,
                          grant,
                          s,
                          tenant,
                          c.target(),
                          c.previous(),
                          key,
                          hash,
                          c.snapshot(),
                          c.effect().name().substring(7),
                          state,
                          c.currency(),
                          c.method(),
                          at.orElse(null),
                          true))
                  .flatMap(
                      row ->
                          writeSelection(
                                  db, tenant, row, c, at.orElse(null), freeIntent, cancelPending)
                              .flatMap(
                                  v ->
                                      subscriptions.event(
                                          db,
                                          tenant,
                                          v,
                                          "SubscriptionPlanChangePrepared",
                                          Map.of(
                                              "operationId",
                                              id(row).toString(),
                                              "planVersionId",
                                              c.target().toString())))
                              .then(
                                  freeIntent
                                      ? Mono.just(row)
                                      : subscriptions
                                          .view(db, tenant)
                                          .flatMap(
                                              current ->
                                                  saveResponse(
                                                      db,
                                                      row,
                                                      current,
                                                      c.effect(),
                                                      cancelPending
                                                          ? ((OffsetDateTime) row.get("applied_at"))
                                                              .toInstant()
                                                          : at.orElse(null)))));
            });
  }

  private Mono<Long> writeSelection(
      DatabaseClient db,
      UUID tenant,
      Map<String, Object> row,
      Choice c,
      Instant at,
      boolean freeIntent,
      boolean cancelPending) {
    var q =
        freeIntent
            ? db.sql(
                "UPDATE taska.subscriptions SET"
                    + " active_plan_change_id=:id,version=version+1,updated_at=:now WHERE"
                    + " tenant_id=:t AND version<9223372036854775807 RETURNING version")
            : db.sql(
                    "UPDATE taska.subscriptions SET"
                        + " selection_generation=:generation,active_plan_change_id=:id,next_plan_version_id=:next,next_effective_at=:at,version=version+1,updated_at=:now"
                        + " WHERE tenant_id=:t AND version<9223372036854775807 RETURNING version")
                .bind("generation", row.get("selection_generation"));
    q = q.bind("t", tenant).bind("now", offset(clock.instant()));
    q = cancelPending ? q.bindNull("id", UUID.class) : q.bind("id", id(row));
    if (!freeIntent) {
      q =
          c.effect() == PlanChange.EffectValue.EFFECT_NEXT_PERIOD
              ? q.bind("next", c.target()).bind("at", offset(Objects.requireNonNull(at)))
              : q.bindNull("next", UUID.class).bindNull("at", OffsetDateTime.class);
    }
    return q.map((r, m) -> r.get("version", Long.class))
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.RESOURCE_EXHAUSTED, "VERSION_EXHAUSTED")));
  }

  private Mono<Map<String, Object>> insert(
      DatabaseClient db,
      VerifiedTenantContext c,
      Grant grant,
      Map<String, Object> s,
      UUID tenant,
      UUID target,
      UUID previous,
      String key,
      byte[] hash,
      String snapshot,
      String effect,
      String state,
      String currency,
      String method,
      Instant at,
      boolean advance) {
    UUID id = UUID.randomUUID();
    long generation =
        advance
            ? increment((Long) s.get("selection_generation"))
            : (Long) s.get("selection_generation");
    var q =
        db.sql(
                """
                INSERT INTO taska.subscription_plan_changes(id,tenant_id,actor_id,request_key,request_hash,owner_id,owner_version,requested_version,selection_generation,previous_plan_version_id,target_plan_version_id,target_terms_snapshot,effect,state,currency,payment_method,effective_at,created_at,applied_at,previous_change_id)
                VALUES(:id,:t,:actor,:key,:hash,:owner,:ownerVersion,:version,:generation,:previous,:target,CAST(:snapshot AS jsonb),:effect,:state,:currency,:method,:at,:now,:applied,:previousChange)
                """)
            .bind("id", id)
            .bind("t", tenant)
            .bind("actor", uuid(c.actorId()))
            .bind("key", key)
            .bind("hash", hash)
            .bind("owner", grant.owner())
            .bind("ownerVersion", grant.ownerVersion())
            .bind("version", s.get("version"))
            .bind("generation", generation)
            .bind("previous", previous)
            .bind("target", target)
            .bind("snapshot", snapshot)
            .bind("effect", effect)
            .bind("state", state)
            .bind("now", offset(clock.instant()));
    q =
        s.get("active_plan_change_id") == null
            ? q.bindNull("previousChange", UUID.class)
            : q.bind("previousChange", s.get("active_plan_change_id"));
    q = currency == null ? q.bindNull("currency", String.class) : q.bind("currency", currency);
    q = method == null ? q.bindNull("method", String.class) : q.bind("method", method);
    q = at == null ? q.bindNull("at", OffsetDateTime.class) : q.bind("at", offset(at));
    q =
        "APPLIED".equals(state)
            ? q.bind("applied", offset(clock.instant()))
            : q.bindNull("applied", OffsetDateTime.class);
    return q.fetch().rowsUpdated().then(operation(db, tenant, id));
  }

  private Mono<PlanChange> admitAndComplete(Map<String, Object> row) {
    if ("REJECTED".equals(row.get("state")))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, row.get("failure_code").toString()));
    return decide(
        row, remote(free.admit(tenant(row), id(row), owner(row), (Long) row.get("owner_version"))));
  }

  private record FreeDecision(FreeProof proof, String failure) {}

  private Mono<PlanChange> decide(Map<String, Object> row, Mono<FreeProof> decision) {
    return decision
        .map(p -> new FreeDecision(p, "FREE_ADMISSION_REJECTED"))
        .onErrorResume(
            DomainException.class,
            e ->
                (e.getMessage().equals("FREE_ELIGIBILITY_USED")
                        || e.getStatus() == DomainStatus.ABORTED
                        || e.getStatus() == DomainStatus.FAILED_PRECONDITION)
                    ? remote(
                            free.cancel(
                                tenant(row), id(row), owner(row), (Long) row.get("owner_version")))
                        .map(
                            p ->
                                new FreeDecision(
                                    p,
                                    e.getMessage().equals("FREE_ELIGIBILITY_USED")
                                        ? "FREE_ELIGIBILITY_USED"
                                        : "FREE_ADMISSION_REJECTED"))
                    : Mono.error(e))
        .flatMap(
            d ->
                d.proof().admitted()
                    ? completeFree(row, d.proof())
                    : rejectFree(row, d.proof(), d.failure()));
  }

  private Mono<PlanChange> rejectFree(
      Map<String, Object> expected, FreeProof proof, String failure) {
    matching(expected, proof);
    if (proof.admitted()) return Mono.error(fail(DomainStatus.DATA_LOSS, "FREE_DECISION_CONFLICT"));
    return tx.execute(
            db ->
                subscriptions
                    .lock(db, tenant(expected))
                    .flatMap(
                        s ->
                            operation(db, tenant(expected), id(expected))
                                .flatMap(
                                    row -> {
                                      if ("REJECTED".equals(row.get("state")))
                                        return Mono.just(true);
                                      if (!"PREPARED".equals(row.get("state"))
                                          || !id(row).equals(s.get("active_plan_change_id")))
                                        return Mono.error(
                                            fail(DomainStatus.DATA_LOSS, "FREE_DECISION_CONFLICT"));
                                      var restore =
                                          db.sql(
                                                  "UPDATE taska.subscriptions SET"
                                                      + " active_plan_change_id=(SELECT p.id FROM"
                                                      + " taska.subscription_plan_changes p WHERE"
                                                      + " p.tenant_id=:t AND p.id=:previous AND"
                                                      + " p.state IN"
                                                      + " ('AWAITING_PAYMENT','SCHEDULED') AND"
                                                      + " p.selection_generation=:generation),version=version+1,updated_at=:now"
                                                      + " WHERE tenant_id=:t AND"
                                                      + " version<9223372036854775807 RETURNING"
                                                      + " version")
                                              .bind("t", tenant(row))
                                              .bind("generation", s.get("selection_generation"))
                                              .bind("now", offset(clock.instant()));
                                      restore =
                                          row.get("previous_change_id") == null
                                              ? restore.bindNull("previous", UUID.class)
                                              : restore.bind(
                                                  "previous", row.get("previous_change_id"));
                                      var restored =
                                          restore.map((r, m) -> r.get("version", Long.class)).one();
                                      return db.sql(
                                              "UPDATE taska.subscription_plan_changes SET"
                                                  + " state='REJECTED',failure_code=:failure"
                                                  + " WHERE tenant_id=:t AND id=:id")
                                          .bind("t", tenant(row))
                                          .bind("id", id(row))
                                          .bind("failure", failure)
                                          .fetch()
                                          .rowsUpdated()
                                          .then(restored)
                                          .switchIfEmpty(
                                              Mono.error(
                                                  fail(
                                                      DomainStatus.RESOURCE_EXHAUSTED,
                                                      "VERSION_EXHAUSTED")))
                                          .flatMap(
                                              v ->
                                                  subscriptions.event(
                                                      db,
                                                      tenant(row),
                                                      v,
                                                      "SubscriptionFreeChangeRejected",
                                                      Map.of("operationId", id(row).toString())))
                                          .thenReturn(true);
                                    })))
        .then(Mono.error(fail(DomainStatus.FAILED_PRECONDITION, failure)));
  }

  private Mono<PlanChange> completeFree(Map<String, Object> expected, FreeProof proof) {
    matching(expected, proof);
    if (!proof.admitted())
      return Mono.error(fail(DomainStatus.DATA_LOSS, "FREE_DECISION_CONFLICT"));
    return tx.execute(
        db ->
            subscriptions
                .lock(db, tenant(expected))
                .flatMap(
                    s ->
                        operation(db, tenant(expected), id(expected))
                            .flatMap(
                                row -> {
                                  if (row.get("response") != null) return Mono.just(response(row));
                                  if (!"PREPARED".equals(row.get("state"))
                                      || !id(row).equals(s.get("active_plan_change_id"))
                                      || !row.get("selection_generation")
                                          .equals(increment((Long) s.get("selection_generation"))))
                                    return Mono.error(
                                        fail(
                                            DomainStatus.FAILED_PRECONDITION,
                                            "PLAN_SELECTION_CHANGED"));
                                  return supersedePending(db, tenant(row))
                                      .then(
                                          db.sql(
                                                  "UPDATE taska.subscription_periods SET"
                                                      + " status='SUPERSEDED' WHERE tenant_id=:t"
                                                      + " AND status='GRANTED'")
                                              .bind("t", tenant(row))
                                              .fetch()
                                              .rowsUpdated())
                                      .then(
                                          db.sql(
                                                  "UPDATE taska.subscriptions SET"
                                                      + " selection_generation=:generation,selected_plan_version_id=:plan,selected_terms_snapshot=(SELECT"
                                                      + " target_terms_snapshot FROM"
                                                      + " taska.subscription_plan_changes WHERE"
                                                      + " tenant_id=:t AND"
                                                      + " id=:op),mode='FREE',current_period_id=NULL,next_plan_version_id=NULL,next_effective_at=NULL,active_plan_change_id=NULL,renewal_enabled=false,autopay_state=CASE"
                                                      + " WHEN autopay_state='CONSENTED' THEN"
                                                      + " 'DISABLED' ELSE autopay_state"
                                                      + " END,version=version+1,updated_at=:now"
                                                      + " WHERE tenant_id=:t AND"
                                                      + " version<9223372036854775807 RETURNING"
                                                      + " version")
                                              .bind("generation", row.get("selection_generation"))
                                              .bind("plan", row.get("target_plan_version_id"))
                                              .bind("op", id(row))
                                              .bind("now", offset(clock.instant()))
                                              .bind("t", tenant(row))
                                              .map((r, m) -> r.get("version", Long.class))
                                              .one())
                                      .switchIfEmpty(
                                          Mono.error(
                                              fail(
                                                  DomainStatus.RESOURCE_EXHAUSTED,
                                                  "VERSION_EXHAUSTED")))
                                      .flatMap(
                                          v ->
                                              subscriptions.event(
                                                  db,
                                                  tenant(row),
                                                  v,
                                                  "SubscriptionPlanChanged",
                                                  Map.of(
                                                      "operationId",
                                                      id(row).toString(),
                                                      "planVersionId",
                                                      row.get("target_plan_version_id")
                                                          .toString())))
                                      .then(
                                          db.sql(
                                                  "UPDATE taska.subscription_plan_changes SET"
                                                      + " state='APPLIED',applied_at=:now WHERE"
                                                      + " tenant_id=:t AND id=:id")
                                              .bind("now", offset(clock.instant()))
                                              .bind("t", tenant(row))
                                              .bind("id", id(row))
                                              .fetch()
                                              .rowsUpdated())
                                      .then(operation(db, tenant(row), id(row)))
                                      .flatMap(
                                          applied ->
                                              subscriptions
                                                  .view(db, tenant(row))
                                                  .flatMap(
                                                      view ->
                                                          saveResponse(
                                                              db,
                                                              applied,
                                                              view,
                                                              PlanChange.EffectValue
                                                                  .EFFECT_IMMEDIATE,
                                                              ((OffsetDateTime)
                                                                      applied.get("applied_at"))
                                                                  .toInstant())))
                                      .map(SubscriptionPlanChanges::response);
                                })));
  }

  /** Bounded recovery within one tenant; the host supplies a fresh verified service context. */
  public Flux<PlanChange> recoverPendingFree(int limit) {
    if (limit < 1 || limit > 100) return Flux.error(invalid());
    return VerifiedTenantContext.current()
        .flatMapMany(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService())
                  || !"recoverFreePlanChange".equals(c.action())
                  || !c.scopes().containsAll(Set.of("write", "plans:recover", "free:admit")))
                return Flux.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              UUID tenant = uuid(c.tenantId());
              return current(c, Command.RECOVER_FREE)
                  .then(
                      tx.execute(
                          db ->
                              db.sql(
                                      "SELECT id FROM taska.subscription_plan_changes WHERE"
                                          + " tenant_id=:t AND state='PREPARED' ORDER BY"
                                          + " created_at,id LIMIT :limit")
                                  .bind("t", tenant)
                                  .bind("limit", limit)
                                  .map((r, m) -> r.get("id", UUID.class))
                                  .all()
                                  .collectList()))
                  .flatMapMany(Flux::fromIterable)
                  .concatMap(this::recoverFree);
            });
  }

  /** Fresh internal recovery purpose, not a conversion of an ordinary caller credential. */
  public Mono<PlanChange> recoverFree(UUID operation) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService())
                  || !"recoverFreePlanChange".equals(c.action())
                  || !c.scopes().containsAll(Set.of("write", "plans:recover", "free:admit")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              UUID tenant = uuid(c.tenantId());
              return current(c, Command.RECOVER_FREE)
                  .then(tx.execute(db -> operation(db, tenant, operation)))
                  .flatMap(
                      row -> {
                        if (row.get("response") != null) return Mono.just(response(row));
                        if (!"PREPARED".equals(row.get("state")))
                          return Mono.error(
                              fail(DomainStatus.FAILED_PRECONDITION, "NOT_A_FREE_INTENT"));
                        return remote(
                                free.accepted(
                                    tenant, id(row), owner(row), (Long) row.get("owner_version")))
                            .onErrorResume(
                                DomainException.class,
                                e ->
                                    e.getStatus() == DomainStatus.NOT_FOUND
                                        ? remote(
                                            free.admit(
                                                tenant,
                                                id(row),
                                                owner(row),
                                                (Long) row.get("owner_version")))
                                        : Mono.error(e))
                            .transform(decision -> decide(row, decision));
                      });
            });
  }

  public Mono<Subscription> cancel(CancelSubscriptionRenewalRpcRequest r) {
    if (!r.getPayload().getConfirmed())
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED"));
    return renewal(uuid(r.getTenantId()), r.getMutation(), true);
  }

  public Mono<Subscription> resume(ResumeSubscriptionRenewalRpcRequest r) {
    return renewal(uuid(r.getTenantId()), r.getMutation(), false);
  }

  private Mono<Subscription> renewal(UUID tenant, MutationOptions mutation, boolean cancel) {
    long expected = expected(mutation);
    String key = key(mutation);
    String action = cancel ? "cancelSubscriptionRenewal" : "resumeSubscriptionRenewal";
    Command command = cancel ? Command.CANCEL_RENEWAL : Command.RESUME_RENEWAL;
    return access
        .authorize(tenant, action, true)
        .flatMap(
            c ->
                current(c, command)
                    .then(
                        subscriptions.command(
                            c,
                            tenant,
                            action,
                            key,
                            c.actorId() + "\n" + expected,
                            db ->
                                subscriptions
                                    .lock(db, tenant)
                                    .flatMap(
                                        s -> {
                                          version(s, expected);
                                          if (!"PAID".equals(s.get("mode")))
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "PAID_SUBSCRIPTION_REQUIRED"));
                                          return current(c, command)
                                              .then(noPreparedFree(db, tenant))
                                              .then(
                                                  db.sql(
                                                          "UPDATE taska.subscriptions SET"
                                                              + " renewal_cancelled=:cancel,renewal_enabled=:enabled,version=version+1,updated_at=:now"
                                                              + " WHERE tenant_id=:t AND"
                                                              + " version<9223372036854775807"
                                                              + " RETURNING version")
                                                      .bind("cancel", cancel)
                                                      .bind("enabled", !cancel)
                                                      .bind("now", offset(clock.instant()))
                                                      .bind("t", tenant)
                                                      .map(
                                                          (row, m) ->
                                                              row.get("version", Long.class))
                                                      .one())
                                              .switchIfEmpty(
                                                  Mono.error(
                                                      fail(
                                                          DomainStatus.RESOURCE_EXHAUSTED,
                                                          "VERSION_EXHAUSTED")))
                                              .flatMap(
                                                  v ->
                                                      subscriptions.event(
                                                          db,
                                                          tenant,
                                                          v,
                                                          cancel
                                                              ? "SubscriptionRenewalCancelled"
                                                              : "SubscriptionRenewalResumed",
                                                          Map.of()))
                                              .then(subscriptions.view(db, tenant));
                                        }))));
  }

  private Mono<Void> noPreparedFree(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT id FROM taska.subscription_plan_changes WHERE tenant_id=:t AND"
                + " state='PREPARED'")
        .bind("t", tenant)
        .fetch()
        .one()
        .flatMap(row -> Mono.<Void>error(fail(DomainStatus.ABORTED, "FREE_TRANSITION_PENDING")));
  }

  private Mono<Void> supersedePending(DatabaseClient db, UUID tenant) {
    return db.sql(
            "UPDATE taska.subscription_plan_changes SET state='SUPERSEDED' WHERE tenant_id=:t AND"
                + " state IN ('AWAITING_PAYMENT','SCHEDULED')")
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Map<String, Object>> byKey(DatabaseClient db, UUID tenant, UUID actor, String key) {
    return db.sql(
            "SELECT * FROM taska.subscription_plan_changes WHERE tenant_id=:t AND actor_id=:actor"
                + " AND request_key=:key")
        .bind("t", tenant)
        .bind("actor", actor)
        .bind("key", key)
        .fetch()
        .one();
  }

  private Mono<Map<String, Object>> operation(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.subscription_plan_changes WHERE tenant_id=:t AND id=:id")
        .bind("t", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "PLAN_CHANGE_NOT_FOUND")));
  }

  private Mono<Map<String, Object>> saveResponse(
      DatabaseClient db,
      Map<String, Object> row,
      Subscription subscription,
      PlanChange.EffectValue effect,
      Instant at) {
    var b =
        PlanChange.newBuilder()
            .setSubscription(subscription)
            .setEffect(effect)
            .setOperationId(id(row).toString());
    if (at != null) b.setEffectiveAt(at.toString());
    return db.sql(
            "UPDATE taska.subscription_plan_changes SET response=:response WHERE tenant_id=:t AND"
                + " id=:id AND response IS NULL")
        .bind("response", Base64.getEncoder().encodeToString(b.build().toByteArray()))
        .bind("t", tenant(row))
        .bind("id", id(row))
        .fetch()
        .rowsUpdated()
        .then(operation(db, tenant(row), id(row)));
  }

  private Mono<Grant> current(VerifiedTenantContext c, Command cmd) {
    return policy
        .require(c, cmd)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "PLAN_POLICY_UNAVAILABLE")));
  }

  private static <T> Mono<T> remote(Mono<T> work) {
    return work.timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "FREE_ADMISSION_UNAVAILABLE")));
  }

  private static void matching(Map<String, Object> r, FreeProof p) {
    if (!tenant(r).equals(p.tenant())
        || !id(r).equals(p.operation())
        || !owner(r).equals(p.owner())
        || !r.get("owner_version").equals(p.ownerVersion()))
      throw fail(DomainStatus.DATA_LOSS, "FREE_ADMISSION_IDENTITY_CONFLICT");
  }

  private static UUID tenant(Map<String, Object> r) {
    return (UUID) r.get("tenant_id");
  }

  private static UUID id(Map<String, Object> r) {
    return (UUID) r.get("id");
  }

  private static UUID owner(Map<String, Object> r) {
    return (UUID) r.get("owner_id");
  }

  private static PlanChange response(Map<String, Object> r) {
    try {
      return PlanChange.parseFrom(Base64.getDecoder().decode((String) r.get("response")));
    } catch (Exception e) {
      throw fail(DomainStatus.DATA_LOSS, "INVALID_PLAN_CHANGE_RECEIPT");
    }
  }

  private static UUID uuid(String v) {
    try {
      UUID id = UUID.fromString(v);
      if (!id.toString().equals(v) || id.equals(new UUID(0, 0))) throw invalid();
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static String key(MutationOptions m) {
    if (!m.getIdempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
    return m.getIdempotencyKey();
  }

  private static long expected(MutationOptions m) {
    try {
      if (!m.hasExpectedVersion())
        throw fail(DomainStatus.FAILED_PRECONDITION, "EXPECTED_VERSION_REQUIRED");
      String s = m.getExpectedVersion();
      if (!s.matches("[1-9][0-9]{0,18}")) throw invalid();
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static void version(Map<String, Object> s, long expected) {
    if (!Long.valueOf(expected).equals(s.get("version")))
      throw fail(DomainStatus.ABORTED, "VERSION_CONFLICT");
  }

  private static long increment(long v) {
    try {
      return Math.incrementExact(v);
    } catch (ArithmeticException e) {
      throw fail(DomainStatus.RESOURCE_EXHAUSTED, "VERSION_EXHAUSTED");
    }
  }

  private static OffsetDateTime offset(Instant t) {
    return t.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  private static byte[] storedHash(Object value) {
    if (value instanceof byte[] bytes) return bytes;
    var buffer = ((java.nio.ByteBuffer) value).asReadOnlyBuffer();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private static byte[] hash(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
