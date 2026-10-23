package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "notification.invitation.scheduler-enabled", matchIfMissing = true)
public class InvitationEmailScheduler {
    private final InvitationEmailQueue queue;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${notification.invitation.poll-interval:5s}")
    public void dispatch() {
        if (running.compareAndSet(false, true)) {
            queue.dispatchBatch().doFinally(signal -> running.set(false))
                    .subscribe(ignored -> { }, error -> log.warn("Invitation email queue is temporarily unavailable"));
        }
    }
}
