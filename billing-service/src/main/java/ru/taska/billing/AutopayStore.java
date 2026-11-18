package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.AutopayMandate;
import ru.taska.api.b2b.v1.ConsentSnapshot;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.SetAutopayConsentRpcRequest;
import ru.taska.billing.AutopayDependencies.Grant;
import ru.taska.billing.AutopayDependencies.Policy;
import ru.taska.billing.AutopayDependencies.Use;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.billing.payment.PaymentProviderRegistry;
import ru.taska.billing.payment.ProviderException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Согласие владельца: subscription lock, текущие полномочия, история отзыва и атомарный outbox. */
public final class AutopayStore {
  final TenantTransactionExecutor transactions;
  final SubscriptionStore subscriptions;
  final Policy policy;
  final PaymentProviderRegistry providers;
  final Clock clock;
  private final FinancialAccess access;
  private final PaymentSettlementDependencies.TenantAuthority root;
  private final JsonMapper json = JsonMapper.builder().build();

  public AutopayStore(
      SubscriptionStore subscriptions,
      Policy policy,
      PaymentProviderRegistry providers,
      Clock clock,
      PaymentSettlementDependencies.TenantAuthority root) {
    this.root = java.util.Objects.requireNonNull(root);
    this.subscriptions = subscriptions;
    this.transactions = subscriptions.transactions();
    this.access = new FinancialAccess(subscriptions.readers());
    this.policy = policy;
    this.providers = providers;
    this.clock = clock;
  }

  public Mono<AutopayMandate> consent(SetAutopayConsentRpcRequest q) {
    UUID tenant = uuid(q.getTenantId());
    var payload = q.getPayload();
    if (!payload.getConfirmed())
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED"));
    String key = q.getMutation().getIdempotencyKey();
    if (!key.matches("[A-Za-z0-9._:-]{1,128}")
        || !q.getMutation().hasExpectedVersion()
        || !q.getMutation().getExpectedVersion().matches("[1-9][0-9]{0,18}"))
      return Mono.error(invalid());
    long expected;
    try {
      expected = Long.parseLong(q.getMutation().getExpectedVersion());
    } catch (NumberFormatException e) {
      return Mono.error(invalid());
    }
    UUID method = payload.hasPaymentMethodId() ? uuid(payload.getPaymentMethodId()) : null;
    if (payload.getConsent()
        && (method == null
            || !payload.hasTermsVersion()
            || payload.getTermsVersion().isBlank()
            || payload.getTermsVersion().length() > 80)) return Mono.error(invalid());
    byte[] hash =
        digest(
            q.getMutation().getExpectedVersion()
                + ":"
                + Base64.getEncoder().encodeToString(payload.toByteArray()));
    var command = new ConsentCommand(q, tenant, method, expected, key, hash);
    return access
        .authorize(tenant, "setAutopayConsent", true)
        .flatMap(
            c ->
                current(c, Use.REPLAY, method)
                    .doOnNext(g -> actor(c, g))
                    .then(transactions.execute(db -> lockedConsent(db, c, command))));
  }

  private record ConsentCommand(
      SetAutopayConsentRpcRequest request,
      UUID tenant,
      UUID method,
      long expected,
      String key,
      byte[] hash) {}

  private Mono<AutopayMandate> lockedConsent(
      DatabaseClient db, VerifiedTenantContext c, ConsentCommand q) {
    return subscriptions
        .lock(db, q.tenant())
        .onErrorMap(
            ru.taska.exception.DomainException.class,
            e ->
                e.getStatus() == DomainStatus.NOT_FOUND
                    ? fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE")
                    : e)
        .flatMap(
            subscription ->
                current(c, Use.REPLAY, q.method())
                    .flatMap(
                        grant -> {
                          actor(c, grant);
                          return receipt(db, q.tenant(), c, q.key(), q.hash())
                              .flatMap(
                                  result -> audit(db, c, grant, result, true).thenReturn(result))
                              .switchIfEmpty(
                                  Mono.defer(() -> changeConsent(db, c, q, subscription)));
                        }));
  }

  private Mono<AutopayMandate> changeConsent(
      DatabaseClient db,
      VerifiedTenantContext c,
      ConsentCommand q,
      Map<String, Object> subscription) {
    if (!Long.valueOf(q.expected()).equals(subscription.get("version")))
      return Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
    long next;
    try {
      next = Math.incrementExact(q.expected());
    } catch (ArithmeticException e) {
      return Mono.error(fail(DomainStatus.RESOURCE_EXHAUSTED, "VERSION_EXHAUSTED"));
    }
    var payload = q.request().getPayload();
    return current(c, payload.getConsent() ? Use.CONSENT : Use.REVOKE, q.method())
        .flatMap(
            grant -> {
              actor(c, grant);
              Mono<AutopayMandate> change =
                  payload.getConsent()
                      ? enable(
                          db, q.tenant(), c, grant, q.method(), payload.getTermsVersion(), next)
                      : disable(db, q.tenant());
              return change.flatMap(
                  result ->
                      db.sql(
                              "UPDATE taska.subscriptions SET"
                                  + " autopay_state=:state,renewal_enabled=CASE WHEN :enableRenewal"
                                  + " AND NOT renewal_cancelled THEN true ELSE renewal_enabled"
                                  + " END,version=:v,updated_at=:now WHERE tenant_id=:t AND"
                                  + " version=:old")
                          .bind("enableRenewal", payload.getConsent())
                          .bind("state", payload.getConsent() ? "CONSENTED" : "DISABLED")
                          .bind("v", next)
                          .bind("now", now())
                          .bind("t", q.tenant())
                          .bind("old", q.expected())
                          .fetch()
                          .rowsUpdated()
                          .flatMap(
                              n ->
                                  n == 1
                                      ? complete(
                                          db, q.tenant(), c, q.key(), q.hash(), result, next, grant)
                                      : Mono.error(
                                          fail(DomainStatus.ABORTED, "VERSION_CONFLICT"))));
            });
  }

  Mono<Grant> current(VerifiedTenantContext c, Use use, UUID method) {
    Mono<Void> restrictions =
        use == Use.DISPATCH
            ? root.current(UUID.fromString(c.tenantId()))
                .timeout(Duration.ofSeconds(5))
                .switchIfEmpty(
                    Mono.error(fail(DomainStatus.UNAVAILABLE, "AUTOPAY_ROOT_UNAVAILABLE")))
                .flatMap(
                    state -> {
                      boolean version;
                      try {
                        long value = Long.parseLong(state.getRestrictionVersion());
                        version =
                            value > 0 && Long.toString(value).equals(state.getRestrictionVersion());
                      } catch (NumberFormatException error) {
                        version = false;
                      }
                      if (!c.tenantId().equals(state.getTenantId())
                          || !version
                          || state.getIndependentRestrictionsList().stream()
                              .anyMatch(
                                  reason ->
                                      reason
                                              == ru.taska.api.b2b.v1.RestrictionCode
                                                  .RESTRICTION_CODE_UNSPECIFIED
                                          || reason
                                              == ru.taska.api.b2b.v1.RestrictionCode.UNRECOGNIZED))
                        return Mono.error(
                            fail(DomainStatus.UNAVAILABLE, "AUTOPAY_ROOT_UNAVAILABLE"));
                      if (state.getLifecycle()
                              != ru.taska.api.b2b.v1.Tenant.LifecycleValue.LIFECYCLE_ACTIVE
                          || state.getIndependentRestrictionsList().stream()
                              .anyMatch(
                                  reason ->
                                      reason
                                              == ru.taska.api.b2b.v1.RestrictionCode
                                                  .RESTRICTION_CODE_OWNER_PAUSE
                                          || reason
                                              == ru.taska.api.b2b.v1.RestrictionCode
                                                  .RESTRICTION_CODE_PLATFORM_BLOCK
                                          || reason
                                              == ru.taska.api.b2b.v1.RestrictionCode
                                                  .RESTRICTION_CODE_PAYMENT_DISPUTE))
                        return Mono.error(
                            fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_UNAVAILABLE"));
                      return Mono.<Void>empty();
                    })
            : Mono.empty();
    return restrictions
        .then(Mono.defer(() -> policy.require(c, use, method)))
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "AUTOPAY_POLICY_UNAVAILABLE")));
  }

  static void actor(VerifiedTenantContext c, Grant g) {
    var claims = RpcContextBridge.details(c).getContext();
    boolean adminCredential =
        java.util.Set.of(
                CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                CredentialKind.CREDENTIAL_KIND_ADMIN_PAT,
                CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
            .contains(claims.getCredentialKind());
    if (c.actorId() == null) throw fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED");
    if (g.administrative() || adminCredential) {
      if (!g.administrative()
          || !adminCredential
          || claims.getAdministrativeLevel() != AdminGrant.LevelValue.LEVEL_MODIFY
          || !(claims.getAdministrativeScope().getKind() == AdminScope.KindValue.KIND_PLATFORM
              || claims.getAdministrativeScope().getKind() == AdminScope.KindValue.KIND_TENANT_SET
                  && claims.getAdministrativeScope().getTenantIdsList().contains(c.tenantId())))
        throw fail(DomainStatus.PERMISSION_DENIED, "ADMIN_AUTHORITY_REQUIRED");
    } else if (!g.owner().toString().equals(c.actorId()))
      throw fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED");
  }

  Mono<PaymentProvider> ready(Grant g) {
    return ready(g, false);
  }

  private Mono<PaymentProvider> ready(Grant g, boolean administrative) {
    if (!g.admissionAllowed() && !administrative)
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "AUTOPAY_UNAVAILABLE"));
    if (providers == null || g.method() == null)
      return Mono.error(fail(DomainStatus.UNAVAILABLE, "AUTOPAY_PROVIDER_NOT_CONFIGURED"));
    return Mono.defer(
        () -> {
          var b = g.method();
          var adapter = approvedProvider(b, b.profileVersion());
          return adapter
              .capabilities(b.merchant())
              .timeout(Duration.ofSeconds(5))
              .filter(c -> c.recurring() && c.statusLookup() && c.signedWebhooks())
              .switchIfEmpty(
                  Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RECURRING_UNSUPPORTED")))
              .thenReturn(adapter);
        });
  }

  PaymentProvider approvedProvider(AutopayDependencies.MethodBinding binding, String profile) {
    if (providers == null) throw fail(DomainStatus.UNAVAILABLE, "AUTOPAY_PROVIDER_NOT_CONFIGURED");
    try {
      return providers.require(binding.provider(), binding.merchant(), profile);
    } catch (ProviderException e) {
      if (e.reason() == ProviderException.Reason.UNSUPPORTED_CAPABILITY)
        throw fail(DomainStatus.FAILED_PRECONDITION, "RECURRING_UNSUPPORTED");
      throw e;
    }
  }

  private Mono<AutopayMandate> enable(
      DatabaseClient db,
      UUID tenant,
      VerifiedTenantContext c,
      Grant grant,
      UUID method,
      String terms,
      long subscriptionVersion) {
    if (grant.termsVersion() == null
        || !grant.termsVersion().equals(terms)
        || grant.termsSha256() == null
        || !grant.termsSha256().matches("[a-f0-9]{64}")
        || grant.termsText() == null
        || grant.termsText().isBlank()
        || grant.termsText().length() > 20000
        || grant.configurationVersion() == null
        || grant.configurationVersion().isBlank()
        || grant.configurationVersion().length() > 100
        || !java.util.HexFormat.of()
            .formatHex(digest(grant.termsText()))
            .equals(grant.termsSha256()))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "CONSENT_TERMS_CHANGED"));
    return method(db, tenant, method)
        .flatMap(
            m -> {
              if (!grant.owner().equals(m.get("owner_id")) || !"ACTIVE".equals(m.get("status")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "PAYMENT_METHOD_NOT_OWNED"));
              return ready(grant, grant.administrative())
                  .delayUntil(
                      adapter ->
                          current(c, Use.CONSENT, method)
                              .map(
                                  fresh -> {
                                    actor(c, fresh);
                                    if (!fresh.equals(grant))
                                      throw fail(DomainStatus.ABORTED, "AUTOPAY_AUTHORITY_CHANGED");
                                    return fresh;
                                  }))
                  .flatMap(
                      adapter -> {
                        if (!grant.method().provider().equals(m.get("provider")))
                          return Mono.error(
                              fail(
                                  DomainStatus.FAILED_PRECONDITION,
                                  "PAYMENT_METHOD_PROVIDER_MISMATCH"));
                        UUID id = UUID.randomUUID();
                        var consentedAt = now();
                        var snapshot = snapshot(c, grant, method, consentedAt);
                        return revoke(db, tenant, "REPLACED")
                            .then(
                                db.sql(
"""
INSERT INTO taska.autopay_mandates(id,tenant_id,owner_id,owner_version,payment_method_id,state,consent_version,
 consented_at,created_subscription_version,revocation_barrier,consent_terms_version,consent_channel,consent_snapshot,
 provider,merchant_account,provider_environment,merchant_profile_version,consent_text,configuration_version)
VALUES(:id,:t,:o,:ov,:m,'ACTIVE',1,:now,:subscriptionVersion,:barrier,:terms,:channel,CAST(:snapshot AS jsonb),:provider,:merchant,:environment,:profile,:text,:configuration)
RETURNING *,consent_snapshot::text AS snapshot_document
""")
                                    .bind("text", grant.termsText())
                                    .bind("configuration", grant.configurationVersion())
                                    .bind("subscriptionVersion", subscriptionVersion)
                                    .bind("id", id)
                                    .bind("t", tenant)
                                    .bind("o", grant.owner())
                                    .bind("ov", grant.ownerVersion())
                                    .bind("m", method)
                                    .bind("now", consentedAt)
                                    .bind("barrier", grant.revocationBarrier())
                                    .bind("terms", terms)
                                    .bind(
                                        "channel",
                                        snapshot
                                            .getConsentChannel()
                                            .name()
                                            .substring("CONSENT_CHANNEL_".length()))
                                    .bind("snapshot", snapshotJson(snapshot))
                                    .bind("provider", grant.method().provider())
                                    .bind("merchant", grant.method().merchantAccount())
                                    .bind("environment", grant.method().environment().name())
                                    .bind("profile", grant.method().profileVersion())
                                    .fetch()
                                    .one()
                                    .map(this::response));
                      });
            });
  }

  Mono<Map<String, Object>> method(DatabaseClient db, UUID tenant, UUID method) {
    return db.sql("SELECT * FROM taska.payment_methods WHERE tenant_id=:t AND id=:id")
        .bind("t", tenant)
        .bind("id", method)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "PAYMENT_METHOD_NOT_FOUND")));
  }

  Mono<Long> revoke(DatabaseClient db, UUID tenant, String reason) {
    return db.sql(
            "UPDATE taska.autopay_mandates SET"
                + " state='REVOKED',consent_version=consent_version+1,revoked_at=:now,revocation_reason=:reason"
                + " WHERE tenant_id=:t AND state='ACTIVE'")
        .bind("now", now())
        .bind("reason", reason)
        .bind("t", tenant)
        .fetch()
        .rowsUpdated();
  }

  private Mono<AutopayMandate> disable(DatabaseClient db, UUID tenant) {
    return revoke(db, tenant, "OWNER_REVOKED")
        .then(latest(db, tenant).map(this::response))
        .switchIfEmpty(Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "NO_AUTOPAY_MANDATE")));
  }

  /** Выполняется после отката неудавшегося admission и не отзывает уже заменённое согласие. */
  Mono<Void> invalidate(UUID tenant, UUID mandate) {
    return transactions.execute(
        db ->
            subscriptions
                .lock(db, tenant)
                .flatMap(
                    subscription -> {
                      long next;
                      try {
                        next = Math.incrementExact((Long) subscription.get("version"));
                      } catch (ArithmeticException e) {
                        return Mono.error(
                            fail(DomainStatus.RESOURCE_EXHAUSTED, "VERSION_EXHAUSTED"));
                      }
                      return db.sql(
                              "UPDATE taska.subscriptions SET"
                                  + " autopay_state='SUSPENDED',version=:v,updated_at=:now WHERE"
                                  + " tenant_id=:t AND autopay_state='CONSENTED' AND EXISTS(SELECT"
                                  + " 1 FROM taska.autopay_mandates WHERE tenant_id=:t AND id=:m"
                                  + " AND state='ACTIVE')")
                          .bind("t", tenant)
                          .bind("m", mandate)
                          .bind("v", next)
                          .bind("now", now())
                          .fetch()
                          .rowsUpdated()
                          .flatMap(
                              changed ->
                                  changed == 0
                                      ? Mono.empty()
                                      : db.sql(
"""
INSERT INTO taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at,attempts)
VALUES(:id,:t,'SUBSCRIPTION',:t,:v,'AUTOPAY_INVALIDATED',1,jsonb_build_object('mandateId',CAST(:m AS text)),:op,'NEW',:now,0)
""")
                                          .bind("id", UUID.randomUUID())
                                          .bind("t", tenant)
                                          .bind("v", next)
                                          .bind("m", mandate.toString())
                                          .bind("op", UUID.randomUUID())
                                          .bind("now", now())
                                          .fetch()
                                          .rowsUpdated()
                                          .then());
                    }));
  }

  Mono<Map<String, Object>> latest(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT *,consent_snapshot::text AS snapshot_document FROM taska.autopay_mandates WHERE"
                + " tenant_id=:t ORDER BY created_subscription_version DESC,id DESC LIMIT 1")
        .bind("t", tenant)
        .fetch()
        .one();
  }

  private Mono<AutopayMandate> receipt(
      DatabaseClient db, UUID tenant, VerifiedTenantContext c, String key, byte[] hash) {
    return db.sql(
            "SELECT request_hash,response->>'proto' AS response FROM taska.idempotency_records"
                + " WHERE tenant_id=:t AND actor_id=:a AND operation_code='setAutopayConsent' AND"
                + " key=:k")
        .bind("t", tenant)
        .bind("a", uuid(c.actorId()))
        .bind("k", key)
        .fetch()
        .one()
        .map(
            r -> {
              Object raw = r.get("request_hash");
              byte[] stored;
              if (raw instanceof byte[] b) stored = b;
              else {
                var buffer = ((ByteBuffer) raw).asReadOnlyBuffer();
                stored = new byte[buffer.remaining()];
                buffer.get(stored);
              }
              if (!MessageDigest.isEqual(hash, stored))
                throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
              try {
                return AutopayMandate.parseFrom(
                    Base64.getDecoder().decode((String) r.get("response")));
              } catch (Exception e) {
                throw fail(DomainStatus.DATA_LOSS, "INVALID_AUTOPAY_RECEIPT");
              }
            });
  }

  private Mono<AutopayMandate> complete(
      DatabaseClient db,
      UUID tenant,
      VerifiedTenantContext c,
      String key,
      byte[] hash,
      AutopayMandate result,
      long version,
      Grant grant) {
    UUID operation = UUID.randomUUID();
    return audit(db, c, grant, result, false)
        .then(
            db.sql(
"""
INSERT INTO taska.idempotency_records(tenant_id,actor_id,operation_code,key,request_hash,operation_id,state,response,created_at,expires_at)
VALUES(:t,:a,'setAutopayConsent',:k,:hash,:op,'SUCCEEDED',jsonb_build_object('proto',CAST(:response AS text)),:now,:expires)
""")
                .bind("t", tenant)
                .bind("a", uuid(c.actorId()))
                .bind("k", key)
                .bind("hash", hash)
                .bind("op", operation)
                .bind("response", Base64.getEncoder().encodeToString(result.toByteArray()))
                .bind("now", now())
                .bind("expires", now().plusDays(1))
                .fetch()
                .rowsUpdated()
                .then(
                    db.sql(
"""
INSERT INTO taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at,attempts)
VALUES(:id,:t,'SUBSCRIPTION',:t,:v,'AUTOPAY_CONSENT_CHANGED',1,jsonb_build_object('mandateId',CAST(:mandate AS text),'state',CAST(:state AS text)),:op,'NEW',:now,0)
""")
                        .bind("id", UUID.randomUUID())
                        .bind("t", tenant)
                        .bind("v", version)
                        .bind("mandate", result.getId())
                        .bind("state", result.getState().name())
                        .bind("op", operation)
                        .bind("now", now())
                        .fetch()
                        .rowsUpdated())
                .thenReturn(result));
  }

  private Mono<Void> audit(
      DatabaseClient db,
      VerifiedTenantContext c,
      Grant grant,
      AutopayMandate result,
      boolean replay) {
    if (!grant.administrative()) return Mono.empty();
    var claims = RpcContextBridge.details(c).getContext();
    var query =
        db.sql(
"""
INSERT INTO taska.autopay_authorization_audit(id,tenant_id,actor_id,owner_id,credential_kind,grant_id,scope_kind,action,request_id,decision,occurred_at)
VALUES(:id,:t,:a,:o,:kind,:grant,:scope,:action,:request,CAST(:decision AS jsonb),:now)
""")
            .bind("id", UUID.randomUUID())
            .bind("t", uuid(c.tenantId()))
            .bind("a", uuid(c.actorId()))
            .bind("o", grant.owner())
            .bind("kind", claims.getCredentialKind().name())
            .bind("scope", claims.getAdministrativeScope().getKind().name())
            .bind("action", c.action())
            .bind("request", c.requestId())
            .bind("now", now())
            .bind(
                "decision",
                json.writeValueAsString(
                    Map.of(
                        "ownerBypass",
                        !c.actorId().equals(grant.owner().toString()),
                        "businessBypass",
                        !grant.admissionAllowed(),
                        "replayed",
                        replay,
                        "mandateId",
                        result.getId())));
    query =
        claims.hasGrantId() && !claims.getGrantId().isBlank()
            ? query.bind("grant", uuid(claims.getGrantId()))
            : query.bindNull("grant", UUID.class);
    return query.fetch().rowsUpdated().then();
  }

  private ConsentSnapshot snapshot(
      VerifiedTenantContext c, Grant g, UUID method, OffsetDateTime consentedAt) {
    var details = RpcContextBridge.details(c);
    var channel =
        switch (details.getContext().getCredentialKind()) {
          case CREDENTIAL_KIND_WEB_TENANT, CREDENTIAL_KIND_WEB_GLOBAL ->
              ConsentSnapshot.ConsentChannelValue.CONSENT_CHANNEL_WEB;
          case CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_ADMIN_PAT ->
              ConsentSnapshot.ConsentChannelValue.CONSENT_CHANNEL_PAT;
          case CREDENTIAL_KIND_USER_OAUTH, CREDENTIAL_KIND_ADMIN_OAUTH ->
              ConsentSnapshot.ConsentChannelValue.CONSENT_CHANNEL_OAUTH;
          default -> throw fail(DomainStatus.PERMISSION_DENIED, "OWNER_SESSION_REQUIRED");
        };
    if ("MCP".equals(details.getHeader().getActionChannel()))
      channel = ConsentSnapshot.ConsentChannelValue.CONSENT_CHANNEL_MCP;
    return ConsentSnapshot.newBuilder()
        .setOwnerId(g.owner().toString())
        .setOwnerVersion(Long.toString(g.ownerVersion()))
        .setActorUserId(c.actorId())
        .setConsentTermsVersion(g.termsVersion())
        .setConsentTermsSha256(g.termsSha256())
        .setConsentChannel(channel)
        .setCredentialKind(details.getContext().getCredentialKind())
        .setPaymentMethodId(method.toString())
        .setConsentedAt(consentedAt.toInstant().toString())
        .setRequestId(c.requestId())
        .build();
  }

  private String snapshotJson(ConsentSnapshot s) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("ownerId", s.getOwnerId());
    fields.put("ownerVersion", s.getOwnerVersion());
    fields.put("actorUserId", s.getActorUserId());
    fields.put("consentTermsVersion", s.getConsentTermsVersion());
    fields.put("consentTermsSha256", s.getConsentTermsSha256());
    fields.put("consentChannel", s.getConsentChannel().name());
    fields.put("credentialKind", s.getCredentialKind().name());
    fields.put("paymentMethodId", s.getPaymentMethodId());
    fields.put("consentedAt", s.getConsentedAt());
    fields.put("requestId", s.getRequestId());
    return json.writeValueAsString(fields);
  }

  private AutopayMandate response(Map<String, Object> row) {
    JsonNode s = json.readTree((String) row.get("snapshot_document"));
    var snapshot =
        ConsentSnapshot.newBuilder()
            .setOwnerId(s.path("ownerId").asString())
            .setOwnerVersion(s.path("ownerVersion").asString())
            .setActorUserId(s.path("actorUserId").asString())
            .setConsentTermsVersion(s.path("consentTermsVersion").asString())
            .setConsentTermsSha256(s.path("consentTermsSha256").asString())
            .setConsentChannel(
                ConsentSnapshot.ConsentChannelValue.valueOf(s.path("consentChannel").asString()))
            .setCredentialKind(
                ru.taska.api.b2b.v1.CredentialKind.valueOf(s.path("credentialKind").asString()))
            .setPaymentMethodId(s.path("paymentMethodId").asString())
            .setConsentedAt(s.path("consentedAt").asString())
            .setRequestId(s.path("requestId").asString())
            .build();
    return AutopayMandate.newBuilder()
        .setId(row.get("id").toString())
        .setOwnerId(row.get("owner_id").toString())
        .setOwnerVersion(row.get("owner_version").toString())
        .setVersion(row.get("consent_version").toString())
        .setState(
            "ACTIVE".equals(row.get("state"))
                ? AutopayMandate.StateValue.STATE_CONSENTED
                : AutopayMandate.StateValue.STATE_DISABLED)
        .setConsentedAt(((OffsetDateTime) row.get("consented_at")).toInstant().toString())
        .setConsentTermsVersion((String) row.get("consent_terms_version"))
        .setConsentChannel(snapshot.getConsentChannel())
        .setConsentSnapshot(snapshot)
        .build();
  }

  OffsetDateTime now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }

  static UUID uuid(String value) {
    try {
      UUID u = UUID.fromString(value);
      if (!u.toString().equals(value)) throw invalid();
      return u;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
