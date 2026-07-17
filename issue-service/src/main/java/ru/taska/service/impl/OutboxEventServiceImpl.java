package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final PayloadSerializer payloadSerializer;

    @Override
    @Transactional
    public Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, Issue issue) {
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .aggregateType(AggregateType.ISSUE.getValue())
                        .aggregateId(issue.getId())
                        .eventType(EventType.ISSUE_CREATED.getValue())
                        .payload(payloadSerializer.createIssueCreatedPayload(issue))
                        .requestId(requestId)
                        .build());
    }

    @Override
    @Transactional
    public Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, Issue issue, EventType type, JsonNode payload) {
        return outboxEventRepository.save(
                OutboxEvent.builder()
                        .aggregateType(AggregateType.ISSUE.getValue())
                        .aggregateId(issue.getId())
                        .eventType(type.getValue())
                        .payload(payload)
                        .requestId(requestId)
                        .build());
    }
}
