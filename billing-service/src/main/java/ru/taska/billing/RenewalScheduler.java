package ru.taska.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CreateInvoiceRequest;
import ru.taska.api.b2b.v1.Invoice;

/** Opt-in dispatcher. No cross-tenant SQL, privileged DB role or synthetic tenant context. */
public final class RenewalScheduler {
  public record Work(UUID tenantId, UUID sourcePeriodId, CreateInvoiceRequest payload) {}

  public interface WorkSource {
    /** Authorized tenant worklist. WIRE notification cadence/delivery is owned by TAS-488. */
    Flux<Work> due(Instant now);
  }

  public interface ExecutionContext {
    /** Install a freshly verified billing-service context, never claims copied from Work. */
    Mono<Invoice> execute(UUID tenant, String action, Mono<Invoice> operation);
  }

  private final InvoiceStore invoices;
  private final WorkSource jobs;
  private final ExecutionContext contexts;
  private final Clock clock;

  public RenewalScheduler(
      InvoiceStore invoices, WorkSource jobs, ExecutionContext contexts, Clock clock) {
    this.invoices = invoices;
    this.jobs = jobs;
    this.contexts = contexts;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${billing.renewal.interval:PT5M}")
  public Mono<Void> tick() {
    return Mono.defer(
        () ->
            Flux.defer(() -> jobs.due(clock.instant()))
                .concatMapDelayError(
                    work ->
                        contexts.execute(
                            work.tenantId(),
                            "issueRenewalInvoice",
                            Mono.defer(
                                () ->
                                    invoices.issueScheduledRenewal(
                                        work.tenantId(), work.sourcePeriodId(), work.payload()))),
                    true,
                    1)
                .then());
  }
}
