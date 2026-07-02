package ru.taska.mapper;

import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.OutboxEventStatus;
import ru.taska.entity.User;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserMapper {



    private final ObjectMapper objectMapper;

    /**
     * Строит OutboxEvent для события приглашения пользователя.
     */
    public OutboxEvent buildUserInvitedOutboxEvent(User user, String invitedBy, String requestId) {
        var payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("email", user.getEmail())
                .put("invitedBy", invitedBy != null ? invitedBy : "system");

        return OutboxEvent.builder()
                .aggregateType(AggregateType.USER.getValue())
                .aggregateId(user.getId())
                .eventType(EventType.USER_INVITED.getValue())
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .requestId(requestId)
                .build();
    }

    /**
     * Строит OutboxEvent для события активации пользователя.
     */
    public OutboxEvent buildUserActivatedOutboxEvent(User user, String requestId) {
        var payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("email", user.getEmail());

        return OutboxEvent.builder()
                .aggregateType(AggregateType.USER.getValue())
                .aggregateId(user.getId())
                .eventType(EventType.USER_ACTIVATED.getValue())
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .requestId(requestId)
                .build();
    }
}
