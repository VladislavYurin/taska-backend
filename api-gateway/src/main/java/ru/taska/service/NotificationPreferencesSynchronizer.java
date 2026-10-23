package ru.taska.service;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Scheduler;
import ru.taska.domain.GatewayContext;
import ru.taska.transport.grpc.GrpcNotificationSettingsClient;

/** Best-effort provisioning for legacy users without delaying the profile response. */
@Component
@Slf4j
public class NotificationPreferencesSynchronizer implements AutoCloseable {
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Sinks.Many<GatewayContext> requests = Sinks.many().unicast()
            .onBackpressureBuffer(new ArrayBlockingQueue<>(256));
    private final Disposable worker;
    private long lastOverflowWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    @Autowired
    public NotificationPreferencesSynchronizer(GrpcNotificationSettingsClient client) {
        this(client, Schedulers.parallel());
    }

    NotificationPreferencesSynchronizer(GrpcNotificationSettingsClient client, Scheduler scheduler) {
        worker = requests.asFlux().publishOn(scheduler, 1)
                .flatMap(context -> Mono.defer(() -> client.get(context))
                        .onErrorResume(error -> {
                            log.warn("[{}] Notification preferences could not be synchronized", context.requestId());
                            return Mono.empty();
                        })
                        .doFinally(signal -> pending.remove(context.userContext().userId())), 4, 1)
                .subscribe();
    }

    /** Coalesce concurrent profile reads; saturation is retried on a later request. */
    public synchronized void submit(GatewayContext context) {
        String user = context.userContext().userId();
        if (worker.isDisposed() || !pending.add(user)) return;
        var result = requests.tryEmitNext(context);
        if (result.isFailure()) {
            pending.remove(user);
            long now = System.nanoTime();
            if (result == Sinks.EmitResult.FAIL_OVERFLOW && now - lastOverflowWarning >= TimeUnit.MINUTES.toNanos(1)) {
                lastOverflowWarning = now;
                log.warn("Notification preferences synchronization queue is full (capacity 256); "
                        + "provisioning is deferred until a later profile or settings request");
            }
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        worker.dispose();
        requests.tryEmitComplete();
        pending.clear();
    }
}
