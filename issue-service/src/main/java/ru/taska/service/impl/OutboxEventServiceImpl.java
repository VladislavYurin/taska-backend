package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final PayloadSerializer payloadSerializer;

    @Override
    @Transactional
    public Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, AggregateType aggregateType, Issue issue) {
        return Mono.deferContextual(context -> outboxEventRepository.save(
                OutboxEvent.builder()
                        .aggregateType(aggregateType.getValue())
                        .aggregateId(issue.getId())
                        .eventType(EventType.ISSUE_CREATED.getValue())
                        .payload(withChannel(payloadSerializer.createIssueCreatedPayload(issue), context))
                        .requestId(requestId)
                        .build()));
    }

    @Override
    @Transactional
    public Mono<OutboxEvent> saveOutboxEvent(
            String requestId,
            String nodeId,
            AggregateType aggregateType,
            UUID aggregateId,
            EventType type,
            JsonNode payload
    ) {
        return Mono.deferContextual(context -> outboxEventRepository.save(
                OutboxEvent.builder()
                        .aggregateType(aggregateType.getValue())
                        .aggregateId(aggregateId)
                        .eventType(type.getValue())
                        .payload(withChannel(payload, context))
                        .requestId(requestId)
                        .build()));
    }
    private JsonNode withChannel(JsonNode payload, reactor.util.context.ContextView context) {
        var channel = ru.taska.domain.ActionMetadata.current(context).channel();
        if (channel == null) return payload;
        if (!(payload instanceof tools.jackson.databind.node.ObjectNode object))
            throw new IllegalStateException("Issue event payload must be an object");
        var copy = object.deepCopy();
        copy.put("channel", channel.name());
        return copy;
    }

}
