package ru.taska.tenant;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.ProvisioningAuthority;

public final class TenantProvisioningWorker {
  private final DatabaseClient dispatch;
  private final java.util.function.Supplier<ProvisioningAuthority> authority;
  private final TenantProvisioning provisioning;

  public TenantProvisioningWorker(
      DatabaseClient dispatch,
      java.util.function.Supplier<ProvisioningAuthority> authority,
      TenantProvisioning provisioning) {
    this.dispatch = dispatch;
    this.authority = authority;
    this.provisioning = provisioning;
  }

  @Scheduled(fixedDelayString = "${taska.tenant.provisioning-poll-ms:1000}")
  public Mono<Void> poll() {
    return Mono.defer(
        () -> {
          var current = authority.get();
          if (current == null) return Mono.empty();
          return dispatch
              .sql(
                  """
                  SELECT tenant_id,operation_id FROM taska.tenant_provisioning_dispatch
                  WHERE completed_at IS NULL AND next_attempt_at<=now()
                  ORDER BY next_attempt_at,tenant_id,operation_id LIMIT 20
                  """)
              .fetch()
              .all()
              .concatMap(
                  row -> {
                    var tenant = (UUID) row.get("tenant_id");
                    var operation = (UUID) row.get("operation_id");
                    return current
                        .execute(tenant, provisioning.resume(tenant, operation))
                        .onErrorComplete();
                  })
              .then();
        });
  }
}
