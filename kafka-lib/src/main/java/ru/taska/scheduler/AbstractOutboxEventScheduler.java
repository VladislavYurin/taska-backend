package ru.taska.scheduler;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.taska.processor.AbstractOutboxEventProcessor;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractOutboxEventScheduler<T> {
    protected final AbstractOutboxEventProcessor<T> processor;

    private final AtomicBoolean isAnyProcessRunning = new AtomicBoolean(false);

    protected AbstractOutboxEventScheduler(AbstractOutboxEventProcessor<T> processor) {
        this.processor = processor;
    }
    protected Mono<Void> publishOutboxEventsInternal(){
        /// Проверяем: занят ли процесс другим потоком. Если занято - выходим
        if (!isAnyProcessRunning.compareAndSet(false,true)){
            log.warn("Another outbox task is still running, skipping publishing");
            return Mono.empty();
        }
        return processor.processOutboxEvents()
                .doOnSuccess(unused -> log.trace("Outbox scheduler iteration completed successfully"))
                .doOnError(ex ->log.error("Outbox scheduler iteration failed: {}", ex.getMessage()))
                .doFinally(signal->isAnyProcessRunning.set(false));
    }
    protected Mono<Void> recoverStuckEventsInternal(){
        /// Проверяем тот же самый общий флаг
        if (!isAnyProcessRunning.compareAndSet(false,true)){
            log.warn("Another outbox task is still running, skipping recovery");
            return Mono.empty();
        }
        return processor.processStuckEvents()
                .doOnSuccess(unused -> log.trace("Recovery scheduler iteration completed successfully"))
                .doOnError(ex -> log.error("Failed to recover stuck events: {}", ex.getMessage()))
                .doFinally(signal->isAnyProcessRunning.set(false));

    }
}
