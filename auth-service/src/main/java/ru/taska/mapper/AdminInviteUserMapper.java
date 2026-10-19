package ru.taska.mapper;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.dto.InviteTokenResponse;
import ru.taska.dto.UserInvitedPayload;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AdminInviteUserMapper {

    private final ObjectMapper objectMapper;

    /** Generates an opaque login independent of email local-part length. */
    public User buildInvitedUserFromRequest(InviteUserRequest request) {
        return User.builder()
            .email(request.getBody().getEmail())
            .login("invited_" + UUID.randomUUID())
            .displayName(request.getBody().getDisplayName())
            .status(UserStatus.INVITED)
            .globalRole(GlobalRole.USER)
            .build();
    }

    public OutboxEvent buildUserInvitedOutboxEvent(InviteTokenResponse inviteTokenResponse, String email, String requestId) {
        JsonNode payloadJson = objectMapper.valueToTree(buildUserInvitedPayload(inviteTokenResponse, email));
        ((tools.jackson.databind.node.ObjectNode) payloadJson).put("expiresAt", inviteTokenResponse.getExpiresAt().toString());
        return OutboxEvent.builder()
                          .aggregateType(AggregateType.USER.getValue())
                          .aggregateId(inviteTokenResponse.getUserId())
                          .eventType(EventType.USER_INVITED.getValue())
                          .payload(payloadJson)
                          .attempts(0)
                          .requestId(requestId)
                          .build();
    }

    private UserInvitedPayload buildUserInvitedPayload(InviteTokenResponse inviteTokenResponse, String email) {
        return UserInvitedPayload.builder()
                                 .userId(inviteTokenResponse.getUserId())
                                 .email(email)
                                 .rawInviteToken(inviteTokenResponse.getRawToken())
                                 .expiresAt(inviteTokenResponse.getExpiresAt())
                                 .build();
    }
}
