package ru.taska.billing.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.PaymentReconciliationResult;

/** Claim one at a time so a bounded batch never exhausts leases waiting for earlier RPC calls. */
public final class PaymentReconciliationPump {
  private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationPump.class);
  private final PaymentReconciliationStore jobs;
  private final Function<PaymentReconciliationStore.Job, Mono<PaymentReconciliationResult>> send;
  private final int size;
  private final AtomicLong manual = new AtomicLong(),
      pending = new AtomicLong(),
      age = new AtomicLong();

  public PaymentReconciliationPump(
      PaymentReconciliationStore jobs,
      Function<PaymentReconciliationStore.Job, Mono<PaymentReconciliationResult>> send,
      MeterRegistry metrics,
      int size) {
    if (size < 1 || size > 100) throw new IllegalArgumentException("Bounded batch required");
    this.jobs = jobs;
    this.send = send;
    this.size = size;
    metrics.gauge("taska.billing.reconciliation.manual", manual);
    metrics.gauge("taska.billing.reconciliation.pending", pending);
    metrics.gauge("taska.billing.reconciliation.oldest.seconds", age);
  }

  @Scheduled(fixedDelayString = "${taska.billing.reconciliation.poll-interval:5s}")
  public Mono<Void> process() {
    return Mono.defer(
        () ->
            Flux.range(0, size)
                .concatMap(
                    n ->
                        jobs.claim(1)
                            .flatMapMany(Flux::fromIterable)
                            .concatMap(
                                j ->
                                    Mono.defer(() -> send.apply(j))
                                        .timeout(java.time.Duration.ofSeconds(60))
                                        .doOnNext(
                                            s -> {
                                              if ("MANUAL".equals(s.getState()))
                                                log.warn(
                                                    "Payment reconciliation requires review"
                                                        + " tenant={} attempt={} correlation={}"
                                                        + " reason={}",
                                                    j.tenantId(),
                                                    j.attemptId(),
                                                    j.correlationId(),
                                                    s.getReasonCode());
                                            })
                                        .onErrorResume(
                                            e ->
                                                jobs.retry(j, "WORKER_UNAVAILABLE")
                                                    .then(Mono.empty()))))
                .then(jobs.summary())
                .doOnNext(
                    s -> {
                      manual.set(s.manual());
                      pending.set(s.pending());
                      age.set(s.oldestSeconds());
                    })
                .then());
  }
}
