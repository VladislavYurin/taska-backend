package ru.taska.transport.kafka;

import org.springframework.stereotype.Service;
import ru.taska.config.OutboxConfig;
import ru.taska.entity.OutboxEvent;
import ru.taska.processor.AbstractOutboxEventProcessor;
import ru.taska.publisher.AbstractOutboxEventPublisher;
import ru.taska.repository.CommonOutboxEventRepository;

import java.util.UUID;

@Service
public class OutboxEventProcessor extends AbstractOutboxEventProcessor<OutboxEvent> {

    protected OutboxEventProcessor(
            AbstractOutboxEventPublisher<OutboxEvent> publisher,
            OutboxConfig config,
            CommonOutboxEventRepository<OutboxEvent> repository
    ) {
        super(publisher, config, repository);
    }

    @Override
    protected UUID getEventId(OutboxEvent event) {
        return event.getId();
    }

    @Override
    protected UUID getAggregateId(OutboxEvent event) {
        return event.getAggregateId();
    }

    @Override
    protected String getEventType(OutboxEvent event) {
        return event.getEventType();
    }

    @Override
    protected int getAttempts(OutboxEvent event) {
        return event.getAttempts() == null ? 0 : event.getAttempts();
    }
}
