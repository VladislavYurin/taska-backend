package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.MerchantConfiguration;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.MerchantConfigurationView;
import ru.taska.api.b2b.v1.PublishMerchantConfigurationRequest;
import ru.taska.api.b2b.v1.ResolveMerchantConfigurationRequest;
import ru.taska.commercial.CommercialConfigurationRules;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Private policy authority. Scalar flags and public events never contain this document. */
public final class MerchantConfigurations {
  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final PlatformFlags access;

  MerchantConfigurations(DatabaseClient db, TransactionalOperator tx, PlatformFlags access) {
    this.db = db;
    this.tx = tx;
    this.access = access;
  }

  private static long number(String s) {
    try {
      if (!s.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
      return Long.parseLong(s);
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private static MerchantConfigurationSnapshot map(Map<String, Object> r) {
    try {
      return MerchantConfigurationSnapshot.newBuilder()
          .setVersion(r.get("version").toString())
          .setEffectiveAt(((OffsetDateTime) r.get("effective_at")).toInstant().toString())
          .setConfiguration(CommercialConfigurationRules.parse(r.get("value").toString()))
          .build();
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
    }
  }

  private Mono<MerchantConfigurationSnapshot> load(Long version) {
    var q =
        db.sql(
            "SELECT version,effective_at,value::text AS value FROM"
                + " taska.platform_configuration_versions WHERE key='MERCHANT_CONFIGURATION'"
                + (version == null ? " ORDER BY version DESC LIMIT 1" : " AND version=:version"));
    if (version != null) q = q.bind("version", version);
    return q.fetch()
        .one()
        .map(MerchantConfigurations::map)
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED")));
  }

  private static MerchantConfigurationView view(MerchantConfigurationSnapshot s) {
    var c = s.getConfiguration();
    var missing = CommercialConfigurationRules.missing(c);
    return MerchantConfigurationView.newBuilder()
        .setVersion(s.getVersion())
        .setEffectiveAt(s.getEffectiveAt())
        .setEnvironment(c.getEnvironment())
        .setEnabled(c.getEnabled())
        .setLiveProfileComplete(
            c.getEnabled()
                && c.getEnvironment() == CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE
                && missing.isEmpty())
        .addAllMissingRequirements(missing)
        .build();
  }

  public Mono<MerchantConfigurationView> get() {
    return access
        .authority("getMerchantConfiguration", false)
        .flatMap(v -> load(null))
        .map(MerchantConfigurations::view);
  }

  public Mono<MerchantConfigurationSnapshot> resolve(ResolveMerchantConfigurationRequest request) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!v.action().equals("resolveMerchantConfiguration")
                  || v.tenantId() != null
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !c.getIssuerService().equals("billing-service")
                  || !v.scopes().containsAll(java.util.Set.of("read", "commercial:resolve")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return load(request.hasVersion() ? number(request.getVersion()) : null);
            });
  }

  public Mono<MerchantConfigurationView> publish(PublishMerchantConfigurationRequest request) {
    return access
        .authority("publishMerchantConfiguration", true)
        .flatMap(
            v ->
                Mono.defer(
                    () -> {
                      if (!request.getConfirmed())
                        throw fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED");
                      MerchantConfiguration c = request.getConfiguration();
                      try {
                        CommercialConfigurationRules.validate(c);
                      } catch (IllegalArgumentException e) {
                        throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
                      }
                      String key = request.getMutation().getIdempotencyKey();
                      TenantRegistryService.uuid(key);
                      long expected = number(request.getMutation().getExpectedVersion());
                      UUID actor = TenantRegistryService.uuid(v.actorId());
                      String json = CommercialConfigurationRules.json(c);
                      byte[] hash =
                          TenantRegistryService.digest(
                              "publishMerchantConfiguration", Long.toString(expected), json);
                      return tx.transactional(
                          db.sql(
                                  "SELECT"
                                      + " pg_advisory_xact_lock(hashtextextended('taska:platformFlags',0))")
                              .fetch()
                              .all()
                              .then(
                                  db.sql(
                                          "SELECT request_hash,flag_version FROM"
                                              + " taska.platform_configuration_receipts WHERE"
                                              + " actor_id=:actor AND key=:key")
                                      .bind("actor", actor)
                                      .bind("key", key)
                                      .map(
                                          (r, m) ->
                                              Map.<String, Object>of(
                                                  "hash",
                                                  r.get("request_hash", byte[].class),
                                                  "version",
                                                  r.get("flag_version", Long.class)))
                                      .one()
                                      .flatMap(
                                          r ->
                                              MessageDigest.isEqual(hash, (byte[]) r.get("hash"))
                                                  ? load((Long) r.get("version"))
                                                  : Mono.error(
                                                      fail(
                                                          DomainStatus.ALREADY_EXISTS,
                                                          "IDEMPOTENCY_CONFLICT")))
                                      .switchIfEmpty(
                                          Mono.defer(
                                              () ->
                                                  load(null)
                                                      .flatMap(
                                                          old -> {
                                                            if (!old.getVersion()
                                                                .equals(Long.toString(expected)))
                                                              return Mono.error(
                                                                  fail(
                                                                      DomainStatus.ABORTED,
                                                                      "VERSION_CONFLICT"));
                                                            long next = Math.addExact(expected, 1);
                                                            return db.sql(
                                                                    "INSERT INTO"
                                                                        + " taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at)"
                                                                        + " VALUES('MERCHANT_CONFIGURATION',:version,'JSON',CAST(:value"
                                                                        + " AS jsonb),clock_timestamp(),:actor,clock_timestamp())")
                                                                .bind("version", next)
                                                                .bind("value", json)
                                                                .bind("actor", actor)
                                                                .fetch()
                                                                .rowsUpdated()
                                                                .then(load(next))
                                                                .flatMap(
                                                                    snapshot ->
                                                                        db.sql(
                                                                                "INSERT INTO"
                                                                                    + " taska.global_outbox_events(id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,status,created_at)"
                                                                                    + " VALUES(:id,'PlatformConfiguration','MERCHANT_CONFIGURATION',:version,'MerchantConfigurationChanged',1,jsonb_build_object('key','MERCHANT_CONFIGURATION','version',CAST(:version"
                                                                                    + " AS text),'effectiveAt',:effective),'NEW',now())")
                                                                            .bind(
                                                                                "id",
                                                                                UUID.randomUUID())
                                                                            .bind("version", next)
                                                                            .bind(
                                                                                "effective",
                                                                                snapshot
                                                                                    .getEffectiveAt())
                                                                            .fetch()
                                                                            .rowsUpdated()
                                                                            .then(
                                                                                db.sql(
                                                                                        "INSERT"
                                                                                            + " INTO"
                                                                                            + " taska.platform_configuration_receipts(actor_id,key,request_hash,flag_key,flag_version)"
                                                                                            + " VALUES(:actor,:key,:hash,'MERCHANT_CONFIGURATION',:version)")
                                                                                    .bind(
                                                                                        "actor",
                                                                                        actor)
                                                                                    .bind(
                                                                                        "key", key)
                                                                                    .bind(
                                                                                        "hash",
                                                                                        hash)
                                                                                    .bind(
                                                                                        "version",
                                                                                        next)
                                                                                    .fetch()
                                                                                    .rowsUpdated())
                                                                            .thenReturn(snapshot));
                                                          }))))
                              .map(MerchantConfigurations::view));
                    }));
  }
}
