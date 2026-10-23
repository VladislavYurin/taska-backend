package ru.taska.service;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@org.springframework.scheduling.annotation.EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class NotificationDeliveryScheduler {
    private final NotificationDeliveryQueue queue;
    private final AtomicBoolean emailRunning = new AtomicBoolean();
    private final AtomicBoolean telegramRunning = new AtomicBoolean();
    @Value("${notification.delivery.scheduler-enabled:true}") private boolean enabled;

    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval:1s}")
    public void email() { if (enabled) run(emailRunning, queue.dispatchEmail()); }
    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval:1s}")
    public void telegram() { if (enabled) run(telegramRunning, queue.dispatchTelegram()); }

    @Scheduled(fixedDelayString = "PT1H")
    public void housekeeping() { if(enabled) queue.cleanup().subscribe(ignored -> {},error -> log.warn("Notification cleanup is temporarily unavailable")); }

    @Scheduled(fixedDelayString = "PT30S")
    public void observe() { if(enabled) queue.observe().subscribe(ignored -> {},error -> log.warn("Notification metrics are temporarily unavailable")); }

    private void run(AtomicBoolean running, Mono<Void> work) {
        if (running.compareAndSet(false,true)) work.doFinally(signal -> running.set(false))
                .subscribe(ignored -> { }, error -> log.warn("Notification delivery queue is temporarily unavailable"));
    }
}
