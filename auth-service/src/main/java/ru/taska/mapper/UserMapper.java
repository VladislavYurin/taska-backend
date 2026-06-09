package ru.taska.mapper;

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

    private static final String USER_AGGREGATE_TYPE = "user";
    private static final String USER_INVITED_EVENT_TYPE = "UserInvited";
    private static final String USER_ACTIVATED_EVENT_TYPE = "UserActivated";

    private final ObjectMapper objectMapper;

    /**
     * Строит OutboxEvent для события приглашения пользователя.
     */
    public OutboxEvent buildUserInvitedOutboxEvent(User user, String invitedBy) {
        var payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("email", user.getEmail())
                .put("invitedBy", invitedBy != null ? invitedBy : "system");

        return OutboxEvent.builder()
                .aggregateType(USER_AGGREGATE_TYPE)
                .aggregateId(user.getId())
                .eventType(USER_INVITED_EVENT_TYPE)
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .build();
    }

    /**
     * Строит OutboxEvent для события активации пользователя.
     */
    public OutboxEvent buildUserActivatedOutboxEvent(User user) {
        var payload = objectMapper.createObjectNode()
                .put("userId", user.getId().toString())
                .put("email", user.getEmail());

        return OutboxEvent.builder()
                .aggregateType(USER_AGGREGATE_TYPE)
                .aggregateId(user.getId())
                .eventType(USER_ACTIVATED_EVENT_TYPE)
                .status(OutboxEventStatus.NEW)
                .payload(payload)
                .attempts(0)
                .build();
    }
}
