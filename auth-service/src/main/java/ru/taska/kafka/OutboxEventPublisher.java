package ru.taska.kafka;

import org.springframework.stereotype.Component;
import reactor.kafka.sender.KafkaSender;
import ru.taska.config.OutboxConfig;
import ru.taska.entity.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.publisher.AbstractOutboxEventPublisher;

import java.util.UUID;

/**
 *
 */
@Component
public class OutboxEventPublisher extends AbstractOutboxEventPublisher<OutboxEvent> {

    private final OutboxConfig config;
    private final OutboxEventMapper mapper;

    public OutboxEventPublisher(
            KafkaSender<String,String> kafkaSender,
            OutboxConfig config,
            OutboxEventMapper mapper
    ){
        super(kafkaSender);
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    protected String getTopic(OutboxEvent event) {
        return config.getTopic();
    }

    @Override
    protected String getKey(OutboxEvent event) {
        return event.getAggregateId().toString();
    }

    @Override
    protected UUID getCorrelationId(OutboxEvent event) {
        return event.getId();
    }

    @Override
    protected String converToJsonFromString(OutboxEvent event) {
        return mapper.toTaskaEventJsonAsString(event);
    }
}
