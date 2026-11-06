package ru.taska.tenant;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.TenantLifecycleAuthority;

public final class TenantCreationExpiryWorker {
  private final DatabaseClient dispatch;
  private final Supplier<TenantLifecycleAuthority> authority;
  private final TenantCreationLifecycle lifecycle;
  private final Clock clock;

  public TenantCreationExpiryWorker(
      DatabaseClient dispatch,
      Supplier<TenantLifecycleAuthority> authority,
      TenantCreationLifecycle lifecycle,
      Clock clock) {
    this.dispatch = dispatch;
    this.authority = authority;
    this.lifecycle = lifecycle;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${taska.tenant.creation-expiry-poll-ms:1000}")
  public Mono<Void> poll() {
    return Mono.defer(
        () -> {
          var current = authority.get();
          if (current == null) return Mono.empty();
          return dispatch
              .sql(
                  """
                  SELECT tenant_id FROM taska.tenant_provisioning_dispatch
                  WHERE creation_expiry_completed_at IS NULL AND creation_deadline_at<=:now
                  ORDER BY creation_deadline_at,tenant_id LIMIT 20
                  """)
              .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
              .fetch()
              .all()
              .concatMap(
                  row -> {
                    UUID tenant = (UUID) row.get("tenant_id");
                    return current.execute(tenant, lifecycle.expire(tenant)).onErrorComplete();
                  })
              .then();
        });
  }
}
