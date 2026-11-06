package ru.taska.billing.ingress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "taska.billing.ingress.enabled", havingValue = "true")
public final class ProviderInboxPump {
  private static final Logger log = LoggerFactory.getLogger(ProviderInboxPump.class);
  private final ProviderInboxStore store;
  private final GrpcProviderInboxWorker worker;

  public ProviderInboxPump(ProviderInboxStore store, GrpcProviderInboxWorker worker) {
    this.store = store;
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${taska.billing.ingress.poll-interval:5s}")
  public Mono<Void> process() {
    return Mono.defer(
        () ->
            store
                .pending(50)
                .flatMapMany(Flux::fromIterable)
                .concatMap(
                    row ->
                        worker
                            .process(row)
                            .onErrorResume(
                                error -> {
                                  log.warn("Provider inbox processing deferred");
                                  return Mono.empty();
                                }))
                .then());
  }

  @Scheduled(fixedDelayString = "${taska.billing.ingress.cleanup-interval:1h}")
  public Mono<Void> purge() {
    return Mono.defer(store::purge);
  }
}
