package ru.taska.billing.reconciliation;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * Private global financial directory. Claims commit before RPC; all completion writes are fenced.
 */
public final class PaymentReconciliationStore {
  public record Job(UUID tenantId, UUID attemptId, UUID correlationId, UUID token, int attempts) {}

  public record Status(String state, String reason) {}

  public record Review(
      UUID tenantId,
      UUID attemptId,
      UUID correlationId,
      int attempts,
      String reason,
      Instant createdAt) {}

  public record Summary(long manual, long pending, long oldestSeconds) {}

  private final DatabaseClient db;
  private final Clock clock;
  private final int maxAttempts;

  public PaymentReconciliationStore(DatabaseClient db, Clock clock, int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > 100)
      throw new IllegalArgumentException("Bounded retries required");
    this.db = db;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  public Mono<List<Job>> claim(int limit) {
    if (limit < 1 || limit > 100)
      return Mono.error(new IllegalArgumentException("Bounded batch required"));
    return Mono.defer(
        () ->
            db.sql(
                    """
                    WITH due AS (SELECT attempt_id FROM taska.payment_reconciliation
                     WHERE state='PENDING' AND next_attempt_at<=:now AND (lease_until IS NULL OR lease_until<=:now)
                     ORDER BY next_attempt_at,attempt_id LIMIT :limit FOR UPDATE SKIP LOCKED)
                    UPDATE taska.payment_reconciliation q SET lease_token=:token,lease_until=:until,attempts=attempts+1
                     FROM due WHERE q.attempt_id=due.attempt_id RETURNING q.*
                    """)
                .bind("now", clock.instant())
                .bind("until", clock.instant().plusSeconds(120))
                .bind("token", UUID.randomUUID())
                .bind("limit", limit)
                .map(
                    (r, m) ->
                        new Job(
                            r.get("tenant_id", UUID.class),
                            r.get("attempt_id", UUID.class),
                            r.get("correlation_id", UUID.class),
                            r.get("lease_token", UUID.class),
                            r.get("attempts", Integer.class)))
                .all()
                .collectList());
  }

  public Mono<Job> lease(UUID tenant, UUID attempt, UUID token) {
    return db.sql(
            "SELECT * FROM taska.payment_reconciliation WHERE tenant_id=:tenant AND attempt_id=:id"
                + " AND lease_token=:token AND lease_until>:now AND state='PENDING'")
        .bind("tenant", tenant)
        .bind("id", attempt)
        .bind("token", token)
        .bind("now", clock.instant())
        .map(
            (r, m) ->
                new Job(
                    tenant,
                    attempt,
                    r.get("correlation_id", UUID.class),
                    token,
                    r.get("attempts", Integer.class)))
        .one()
        .switchIfEmpty(Mono.error(new IllegalStateException("RECONCILIATION_LEASE_LOST")));
  }

  public Mono<Status> done(Job job) {
    return finish(job, "DONE", "CONFIRMED");
  }

  public Mono<Status> manual(Job job, String reason) {
    return finish(job, "MANUAL", reason);
  }

  public Mono<Status> retry(Job job, String reason) {
    return finish(job, job.attempts() >= maxAttempts ? "MANUAL" : "PENDING", reason);
  }

  private Mono<Status> finish(Job j, String state, String reason) {
    if (!reason.matches("[A-Z_]{1,80}"))
      return Mono.error(new IllegalArgumentException("Stable reason required"));
    long delay = Math.min(3600, 30L * (1L << Math.min(j.attempts() - 1, 7)));
    return Mono.defer(
        () ->
            db.sql(
                    """
                    UPDATE taska.payment_reconciliation SET state=:state,reason_code=:reason,next_attempt_at=:next,lease_token=NULL,lease_until=NULL
                     WHERE tenant_id=:tenant AND attempt_id=:id AND lease_token=:token AND lease_until>:now AND state='PENDING' RETURNING state,reason_code
                    """)
                .bind("state", state)
                .bind("reason", reason)
                .bind("next", clock.instant().plusSeconds(delay))
                .bind("tenant", j.tenantId())
                .bind("id", j.attemptId())
                .bind("token", j.token())
                .bind("now", clock.instant())
                .map(
                    (r, m) ->
                        new Status(
                            r.get("state", String.class), r.get("reason_code", String.class)))
                .one());
  }

  /**
   * Authorized internal operator handoff; immutable tenant/correlation and total attempts retained.
   */
  public Mono<Void> replay(UUID tenant, UUID attempt) {
    return ru.taska.security.tenant.VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claims = ru.taska.security.tenant.RpcContextBridge.details(c).getContext();
              if (!tenant.toString().equals(c.tenantId())
                  || c.actorId() != null
                  || !"replayPaymentReconciliation".equals(c.action())
                  || claims.getCredentialKind()
                      != ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService())
                  || !c.scopes().contains("write")
                  || !c.scopes().contains("payment:reconcile:replay"))
                return Mono.error(
                    new ru.taska.exception.DomainException(
                        ru.taska.exception.DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return db.sql(
                      "UPDATE taska.payment_reconciliation SET"
                          + " state='PENDING',reason_code='OPERATOR_REPLAY',next_attempt_at=:now"
                          + " WHERE tenant_id=:tenant AND attempt_id=:id AND state='MANUAL' AND"
                          + " lease_token IS NULL")
                  .bind("tenant", tenant)
                  .bind("id", attempt)
                  .bind("now", clock.instant())
                  .fetch()
                  .rowsUpdated()
                  .then();
            });
  }

  public Mono<List<Review>> manual(int limit) {
    if (limit < 1 || limit > 100)
      return Mono.error(new IllegalArgumentException("Bounded batch required"));
    return db.sql(
            "SELECT * FROM taska.payment_reconciliation WHERE state='MANUAL' ORDER BY"
                + " created_at,attempt_id LIMIT :limit")
        .bind("limit", limit)
        .map(
            (r, m) ->
                new Review(
                    r.get("tenant_id", UUID.class),
                    r.get("attempt_id", UUID.class),
                    r.get("correlation_id", UUID.class),
                    r.get("attempts", Integer.class),
                    r.get("reason_code", String.class),
                    r.get("created_at", Instant.class)))
        .all()
        .collectList();
  }

  public Mono<Summary> summary() {
    return db.sql(
            "SELECT count(*) FILTER(WHERE state='MANUAL') AS manual,count(*) FILTER(WHERE"
                + " state='PENDING') AS pending,coalesce(extract(epoch FROM (:now-min(created_at)"
                + " FILTER(WHERE state<>'DONE')))::bigint,0) AS age FROM"
                + " taska.payment_reconciliation")
        .bind("now", clock.instant())
        .map(
            (r, m) ->
                new Summary(
                    r.get("manual", Long.class),
                    r.get("pending", Long.class),
                    Math.max(0, r.get("age", Long.class))))
        .one();
  }
}
