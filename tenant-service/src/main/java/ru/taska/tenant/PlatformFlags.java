package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListPlatformFlagsRpcRequest;
import ru.taska.api.b2b.v1.PlatformFlag;
import ru.taska.api.b2b.v1.PlatformFlagPage;
import ru.taska.api.b2b.v1.UpdatePlatformFlagRpcRequest;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Global allowlisted configuration. Every administrative call requires fresh online authority. */
public final class PlatformFlags {
  private static final String ROWS =
      "SELECT DISTINCT ON(v.key) v.*,d.owner,d.default_value::text AS"
          + " default_text,d.minimum_value,d.maximum_value,v.value::text AS value_text FROM"
          + " taska.platform_configuration_versions v JOIN taska.platform_configuration_definitions"
          + " d USING(key)";
  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final PlatformFlagCursor cursors;

  public PlatformFlags(DatabaseClient db, TransactionalOperator tx, PlatformFlagCursor cursors) {
    this.db = db;
    this.tx = tx;
    this.cursors = cursors;
  }

  private Mono<VerifiedTenantContext> authority(String action, boolean write) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              boolean web =
                  c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                      && c.hasSessionId()
                      && !c.getSessionId().isBlank();
              boolean integration =
                  (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
                          || c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
                      && c.hasCredentialId()
                      && !c.getCredentialId().isBlank()
                      && c.hasGrantId()
                      && !c.getGrantId().isBlank()
                      && c.hasGrantGeneration()
                      && !c.getGrantGeneration().isBlank();
              boolean level =
                  c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_MODIFY
                      || (!write && c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_READ);
              // RpcContextSecurity's required Authority checks current GLOBAL_ADMIN and MFA/grant
              // online.
              if (!action.equals(v.action())
                  || v.actorId() == null
                  || v.tenantId() != null
                  || c.getActorGeneration().isBlank()
                  || (!web && !integration)
                  || !level
                  || c.getAdministrativeScope().getKind() != AdminScope.KindValue.KIND_PLATFORM
                  || !v.scopes().contains(write ? "write" : "read"))
                throw fail(DomainStatus.PERMISSION_DENIED, "ADMIN_SCOPE_DENIED");
              return v;
            });
  }

  private static long number(String s) {
    try {
      if (!s.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
      return Long.parseLong(s);
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private static PlatformFlag map(Map<String, Object> row) {
    var f =
        PlatformFlag.newBuilder()
            .setKey(row.get("key").toString())
            .setType(PlatformFlag.TypeValue.valueOf("TYPE_" + row.get("value_type")))
            .setVersion(row.get("version").toString())
            .setEffectiveAt(((OffsetDateTime) row.get("effective_at")).toInstant().toString())
            .setOwner(row.get("owner").toString());
    if (f.getType() == PlatformFlag.TypeValue.TYPE_BOOLEAN)
      f.setBooleanValue(Boolean.parseBoolean(row.get("value_text").toString()))
          .setDefaultBooleanValue(Boolean.parseBoolean(row.get("default_text").toString()));
    else
      f.setIntegerValue(row.get("value_text").toString())
          .setDefaultIntegerValue(row.get("default_text").toString())
          .setMinimumValue(row.get("minimum_value").toString())
          .setMaximumValue(row.get("maximum_value").toString());
    return f.build();
  }

  private Mono<PlatformFlag> version(String key, long version) {
    return db.sql(ROWS + " WHERE v.key=:key AND v.version=:version ORDER BY v.key,v.version DESC")
        .bind("key", key)
        .bind("version", version)
        .fetch()
        .one()
        .map(PlatformFlags::map);
  }

  private static String authorityBinding(VerifiedTenantContext v) {
    var c = RpcContextBridge.details(v).getContext();
    return c.toBuilder().clearIssuedAt().clearExpiresAt().clearAssertionId().build().toString();
  }

  public Mono<PlatformFlagPage> list(ListPlatformFlagsRpcRequest request) {
    return authority("listPlatformFlags", false)
        .flatMap(
            v -> {
              int limit = request.getPage().getLimit() == 0 ? 50 : request.getPage().getLimit();
              if (limit < 1 || limit > 100)
                throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
              return db.sql(ROWS + " ORDER BY v.key,v.version DESC")
                  .fetch()
                  .all()
                  .map(PlatformFlags::map)
                  .collectList()
                  .map(
                      flags -> {
                        // Includes every generation and the entire verified authority envelope,
                        // never raw credentials.
                        String generation =
                            flags.stream()
                                .map(f -> f.getKey() + "=" + f.getVersion())
                                .reduce("", (a, b) -> a + ";" + b);
                        String binding =
                            Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                    TenantRegistryService.digest(
                                        "platformFlags/v1",
                                        authorityBinding(v),
                                        Integer.toString(limit),
                                        generation));
                        String after =
                            request.getPage().hasCursor()
                                ? cursors.decode(request.getPage().getCursor(), binding)
                                : "";
                        List<PlatformFlag> selected =
                            flags.stream().filter(f -> f.getKey().compareTo(after) > 0).toList();
                        var out =
                            PlatformFlagPage.newBuilder()
                                .addAllItems(selected.subList(0, Math.min(limit, selected.size())));
                        if (selected.size() > limit)
                          out.setNextCursor(
                              cursors.encode(binding, selected.get(limit - 1).getKey()));
                        return out.build();
                      });
            });
  }

  public Mono<PlatformFlag> update(UpdatePlatformFlagRpcRequest request) {
    return authority("updatePlatformFlag", true)
        .flatMap(
            v ->
                Mono.defer(
                    () -> {
                      UUID actor = TenantRegistryService.uuid(v.actorId());
                      String key = request.getFlagKey(),
                          intent = request.getMutation().getIdempotencyKey();
                      TenantRegistryService.uuid(intent);
                      if (!request.getMutation().hasExpectedVersion())
                        throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
                      long expected = number(request.getMutation().getExpectedVersion());
                      var p = request.getPayload();
                      if (p.hasBooleanValue() == p.hasIntegerValue())
                        throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
                      String value =
                          p.hasBooleanValue()
                              ? Boolean.toString(p.getBooleanValue())
                              : Long.toString(number(p.getIntegerValue()));
                      String type = p.hasBooleanValue() ? "BOOLEAN" : "DURATION_SECONDS";
                      byte[] hash =
                          TenantRegistryService.digest(
                              "updatePlatformFlag", key, type, value, Long.toString(expected));
                      return tx.transactional(
                          db.sql(
                                  "SELECT"
                                      + " pg_advisory_xact_lock(hashtextextended('taska:platformFlags',0))")
                              .fetch()
                              .all()
                              .then(
                                  db.sql(
                                          "SELECT request_hash,flag_key,flag_version FROM"
                                              + " taska.platform_configuration_receipts WHERE"
                                              + " actor_id=:actor AND key=:intent")
                                      .bind("actor", actor)
                                      .bind("intent", intent)
                                      .map(
                                          (r, m) ->
                                              Map.<String, Object>of(
                                                  "request_hash",
                                                  r.get("request_hash", byte[].class),
                                                  "flag_key",
                                                  r.get("flag_key", String.class),
                                                  "flag_version",
                                                  r.get("flag_version", Long.class)))
                                      .one()
                                      .flatMap(
                                          r -> {
                                            if (!MessageDigest.isEqual(
                                                hash, (byte[]) r.get("request_hash")))
                                              return Mono.error(
                                                  fail(
                                                      DomainStatus.ALREADY_EXISTS,
                                                      "IDEMPOTENCY_CONFLICT"));
                                            return version(
                                                r.get("flag_key").toString(),
                                                ((Number) r.get("flag_version")).longValue());
                                          })
                                      .switchIfEmpty(
                                          Mono.defer(
                                              () ->
                                                  db.sql(
                                                          ROWS
                                                              + " WHERE v.key=:key ORDER BY"
                                                              + " v.key,v.version DESC LIMIT 1")
                                                      .bind("key", key)
                                                      .fetch()
                                                      .one()
                                                      .switchIfEmpty(
                                                          Mono.error(
                                                              fail(
                                                                  DomainStatus.NOT_FOUND,
                                                                  "RESOURCE_NOT_FOUND")))
                                                      .flatMap(
                                                          row -> {
                                                            if (((Number) row.get("version"))
                                                                    .longValue()
                                                                != expected)
                                                              return Mono.error(
                                                                  fail(
                                                                      DomainStatus.ABORTED,
                                                                      "VERSION_CONFLICT"));
                                                            if (!row.get("value_type").equals(type)
                                                                || (!p.hasBooleanValue()
                                                                    && (number(value)
                                                                            < ((Number)
                                                                                    row.get(
                                                                                        "minimum_value"))
                                                                                .longValue()
                                                                        || number(value)
                                                                            > ((Number)
                                                                                    row.get(
                                                                                        "maximum_value"))
                                                                                .longValue())))
                                                              return Mono.error(
                                                                  fail(
                                                                      DomainStatus.INVALID_ARGUMENT,
                                                                      "VALIDATION_FAILED"));
                                                            long next = Math.addExact(expected, 1);
                                                            return db.sql(
                                                                    "INSERT INTO"
                                                                        + " taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at)"
                                                                        + " VALUES(:key,:version,:type,CAST(:value"
                                                                        + " AS jsonb),clock_timestamp(),:actor,clock_timestamp())")
                                                                .bind("key", key)
                                                                .bind("version", next)
                                                                .bind("type", type)
                                                                .bind("value", value)
                                                                .bind("actor", actor)
                                                                .fetch()
                                                                .rowsUpdated()
                                                                .then(version(key, next))
                                                                .flatMap(
                                                                    result ->
                                                                        db.sql(
                                                                                "INSERT INTO"
                                                                                    + " taska.global_outbox_events(id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,status,created_at)"
                                                                                    + " VALUES(:id,'PlatformConfiguration',:key,:version,'PlatformConfigurationChanged',1,jsonb_build_object('key',:key,'version',CAST(:version"
                                                                                    + " AS text),'effectiveAt',:effective,'type',:type,'value',CAST(:value"
                                                                                    + " AS jsonb),'cacheMaxAgeSeconds',30),'NEW',now())")
                                                                            .bind(
                                                                                "id",
                                                                                UUID.randomUUID())
                                                                            .bind("key", key)
                                                                            .bind("version", next)
                                                                            .bind(
                                                                                "effective",
                                                                                result
                                                                                    .getEffectiveAt())
                                                                            .bind("type", type)
                                                                            .bind("value", value)
                                                                            .fetch()
                                                                            .rowsUpdated()
                                                                            .then(
                                                                                db.sql(
                                                                                        "INSERT"
                                                                                            + " INTO"
                                                                                            + " taska.platform_configuration_receipts(actor_id,key,request_hash,flag_key,flag_version)"
                                                                                            + " VALUES(:actor,:intent,:hash,:key,:version)")
                                                                                    .bind(
                                                                                        "actor",
                                                                                        actor)
                                                                                    .bind(
                                                                                        "intent",
                                                                                        intent)
                                                                                    .bind(
                                                                                        "hash",
                                                                                        hash)
                                                                                    .bind(
                                                                                        "key", key)
                                                                                    .bind(
                                                                                        "version",
                                                                                        next)
                                                                                    .fetch()
                                                                                    .rowsUpdated())
                                                                            .thenReturn(result));
                                                          })))));
                    }));
  }

  /** Online read on the caller's transaction; accepted deadlines never consult this again. */
  static Mono<Duration> duration(DatabaseClient transaction, String key) {
    return transaction
        .sql(
            "SELECT value::text AS seconds FROM taska.platform_configuration_versions WHERE"
                + " key=:key AND value_type='DURATION_SECONDS' ORDER BY version DESC LIMIT 1")
        .bind("key", key)
        .map((r, m) -> Duration.ofSeconds(number(r.get("seconds", String.class))))
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED")));
  }
}
