package ru.taska.kafka;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.taska.entity.OutboxEvent;
import ru.taska.processor.AbstractOutboxEventProcessor;
import ru.taska.sheduler.AbstractOutboxEventScheduler;

@Component
public class OutboxEventScheduler extends AbstractOutboxEventScheduler<OutboxEvent> {

    protected OutboxEventScheduler(AbstractOutboxEventProcessor<OutboxEvent> processor) {
        super(processor);
    }

    @Scheduled(fixedDelayString = "${app.kafka.outbox.polling-interval}")
    public void publishOutboxEvents() {
        publishOutboxEventsInternal().subscribe();
    }

    @Scheduled(fixedDelayString = "${app.kafka.outbox.recovery-interval}")
    public void recoverStuckEvents() {
        recoverStuckEventsInternal().subscribe();
    }
}
