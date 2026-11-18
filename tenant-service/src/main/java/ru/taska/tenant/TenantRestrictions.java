package ru.taska.tenant;

import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.RestrictionCode;

/** Independent primary reasons. Public reads never include the administrative free-text reason. */
final class TenantRestrictions {
  static final String SELECT =
      "ARRAY(SELECT r.kind FROM taska.tenant_restrictions r WHERE r.tenant_id=t.id AND r.active"
          + " ORDER BY r.kind,r.source_id) AS independent_restrictions";

  static Set<RestrictionCode> codes(Object raw) {
    if (raw == null) return Set.of();
    if (!(raw instanceof String[] values))
      throw new IllegalStateException("Invalid primary restrictions");
    var result = EnumSet.noneOf(RestrictionCode.class);
    for (String value : values)
      result.add(
          switch (value) {
            case "OWNER_PAUSE" -> RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE;
            case "PLATFORM_BLOCK" -> RestrictionCode.RESTRICTION_CODE_PLATFORM_BLOCK;
            case "NONPAYMENT" -> RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED;
            case "QUOTA_EXCEEDED" -> RestrictionCode.RESTRICTION_CODE_OVER_QUOTA;
            case "FINANCIAL_DISPUTE" -> RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE;
            default -> throw new IllegalStateException("Unknown primary restriction");
          });
    return Collections.unmodifiableSet(result);
  }

  static Mono<Map<String, Object>> decorate(DatabaseClient db, Map<String, Object> tenant) {
    return db.sql("SELECT " + SELECT + " FROM taska.tenants t WHERE t.id=:tenant")
        .bind("tenant", tenant.get("id"))
        .fetch()
        .one()
        .map(
            reasons -> {
              var result = new HashMap<>(tenant);
              result.putAll(reasons);
              return result;
            });
  }
}
