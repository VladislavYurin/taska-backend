package ru.taska.issuebootstrap;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionServiceGrpc;
import ru.taska.issuestorage.*;
import ru.taska.security.tenant.*;

/** Explicit tenant worklist only. Every attempt gets fresh managed SERVICE authority. */
@Component
@Profile("tenant-storage")
@EnableScheduling
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public final class AttachmentCleanupRecovery {
  private final AttachmentLifecycleStore store;
  private final AttachmentDeletions deletions;
  private final AttachmentUploads uploads;
  private final ManagedServiceContexts contexts;
  private final MeterRegistry metrics;
  private final List<UUID> tenants;
  private final Map<UUID, UUID> uploadCursors = new HashMap<>();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong pending = new AtomicLong(), lastScan = new AtomicLong();

  public AttachmentCleanupRecovery(
      AttachmentLifecycleStore store,
      AttachmentDeletions deletions,
      AttachmentUploads uploads,
      ManagedServiceContexts contexts,
      MeterRegistry metrics,
      @Value("${taska.attachments.cleanup-tenants:}") String configured) {
    this.store = store;
    this.deletions = deletions;
    this.uploads = uploads;
    this.contexts = contexts;
    this.metrics = metrics;
    tenants =
        configured.isBlank()
            ? List.of()
            : Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(UUID::fromString)
                .distinct()
                .toList();
    if (tenants.size() > 100)
      throw new IllegalArgumentException("Bounded attachment cleanup worklist required");
    metrics.gauge("taska.attachments.cleanup.pending", pending);
    metrics.gauge("taska.attachments.cleanup.last_scan_epoch_seconds", lastScan);
  }

  private <T> Mono<T> authorized(
      UUID tenant, boolean upload, java.util.function.Supplier<Mono<T>> work) {
    return contexts
        .authorize(
            "STORAGE_CLEANUP",
            "issue-service",
            upload
                ? AttachmentAdmissionServiceGrpc.getReconcileUploadsMethod().getFullMethodName()
                : AttachmentAdmissionServiceGrpc.getReconcileDeletionsMethod().getFullMethodName(),
            upload ? "reconcileAttachmentUploads" : "reconcileAttachmentDeletions",
            tenant,
            Set.of("write", "storage:recover"),
            new RpcContextSecurity.Policy(true, true))
        .flatMap(c -> RpcContextBridge.install(Mono.defer(work), c));
  }

  @Scheduled(fixedDelayString = "${taska.attachments.cleanup-interval:30s}")
  public void tick() {
    if (tenants.isEmpty() || !running.compareAndSet(false, true)) return;
    AtomicLong total = new AtomicLong();
    AtomicBoolean scanComplete = new AtomicBoolean(true);
    Flux.fromIterable(tenants)
        .concatMap(
            t ->
                authorized(
                        t,
                        false,
                        () -> store.pending().doOnNext(total::addAndGet).then(store.due(null, 100)))
                    .flatMapMany(
                        ids ->
                            Flux.fromIterable(ids)
                                .concatMap(
                                    id ->
                                        authorized(t, false, () -> deletions.process(id))
                                            .doOnNext(
                                                n ->
                                                    metrics
                                                        .counter(
                                                            "taska.attachments.cleanup.attempts",
                                                            "kind",
                                                            "delete",
                                                            "outcome",
                                                            n == 0
                                                                ? "completed"
                                                                : n == 2 ? "data_loss" : "deferred")
                                                        .increment())))
                    .then(authorized(t, true, () -> uploads.reconcile(uploadCursors.get(t), 1)))
                    .doOnNext(
                        r -> {
                          if (r.next().isEmpty()) uploadCursors.remove(t);
                          else uploadCursors.put(t, UUID.fromString(r.next()));
                          metrics
                              .counter(
                                  "taska.attachments.cleanup.attempts",
                                  "kind",
                                  "upload",
                                  "outcome",
                                  r.dataLoss() > 0
                                      ? "data_loss"
                                      : r.failed() > 0 ? "deferred" : "completed")
                              .increment(r.processed());
                        })
                    .onErrorResume(
                        e -> {
                          scanComplete.set(false);
                          metrics.counter("taska.attachments.cleanup.scan_failures").increment();
                          return Mono.empty();
                        }))
        .doOnComplete(
            () -> {
              if (scanComplete.get()) {
                pending.set(total.get());
                lastScan.set(java.time.Instant.now().getEpochSecond());
              }
            })
        .doFinally(s -> running.set(false))
        .subscribe();
  }
}
