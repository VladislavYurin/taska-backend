package ru.taska.sheduler;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.taska.processor.AbstractOutboxEventProcessor;

@Slf4j
public abstract class AbstractOutboxEventScheduler<T> {
    protected final AbstractOutboxEventProcessor<T> processor;

    protected AbstractOutboxEventScheduler(AbstractOutboxEventProcessor<T> processor) {
        this.processor = processor;
    }
    protected Mono<Void> publishOutboxEventsInternal(){
        return processor.processOutboxEvents()
                .doOnSuccess(unused -> log.trace("Outbox scheduler iteration completed successfully"))
                .doOnError(ex ->log.error("Outbox scheduler iteration failed: {}", ex.getMessage()));
    }
    protected Mono<Void> recoverStuckEventsInternal(){
        return processor.processStuckEvents()
                .doOnSuccess(unused -> log.trace("Recovery scheduler iteration completed successfully"))
                .doOnError(ex -> log.error("Failed to recover stuck events: {}", ex.getMessage()));
    }
}
