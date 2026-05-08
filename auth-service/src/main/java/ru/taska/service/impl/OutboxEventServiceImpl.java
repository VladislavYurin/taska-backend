package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.User;
import ru.taska.domain.UserStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.OutboxEventService;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Создает событие по изменению статуса пользователя и сохраняет в outbox.
     */
    public Mono<OutboxEvent> registerStatusChange(User user, UserStatus status) {
        String eventType = switch (status) {
            case INVITED -> "UserInvited";
            case ACTIVE -> "UserActivated";
            case BLOCKED -> "UserBlocked";
        };

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(user.getId())
                .eventType(eventType)
                .payload(objectMapper.valueToTree(user))
                .attempts(0)
                .publishedAt(Instant.now())
                .build();

        return outboxEventRepository.save(event);
    }
}
