package ru.taska.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Ограниченный opt-in worker; повторная выдача задания не создаёт вторую попытку. */
public final class AutopayScheduler {
  public record Work(UUID tenant, UUID invoice) {}

  @FunctionalInterface
  public interface WorkSource {
    /** Авторизованный durable-список уже выданных счетов продления; без глобального tenant SQL. */
    Flux<Work> due(Instant now);
  }

  private final WorkSource work;
  private final GrpcAutopayWorker invoker;
  private final Clock clock;
  private final int batch;

  public AutopayScheduler(WorkSource work, GrpcAutopayWorker invoker, Clock clock, int batch) {
    if (batch < 1 || batch > 100) throw new IllegalArgumentException("Bounded batch required");
    this.work = work;
    this.invoker = invoker;
    this.clock = clock;
    this.batch = batch;
  }

  @Scheduled(fixedDelayString = "${taska.billing.autopay.interval:PT5M}")
  public Mono<Void> tick() {
    return Mono.defer(
        () ->
            Flux.defer(() -> work.due(clock.instant()))
                .take(batch)
                .concatMapDelayError(w -> invoker.dispatch(w.tenant(), w.invoice()), true, 1)
                .then());
  }
}
