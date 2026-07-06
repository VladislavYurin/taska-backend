package ru.taska.mapper;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
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

    //TODO: здесь создается логин из email'а, т. к. нельзя сохранить юзера без логина.
    // Это происходит из-за not null констрейнта на поле "login" в БД.
    // Согласно документации Confluence, login не может передаваться в грпс-запросе InviteUserRequest.
    // Поэтому пока, при регистрации пользователя админом, рандомный login генерируется из переданного эмайла в этом методе.
    public User buildInvitedUserFromRequest(InviteUserRequest request) {
        return User.builder()
            .email(request.getBody().getEmail())
            .login(request.getBody().getEmail().split("@")[0] + "_" + UUID.randomUUID().toString().substring(0, 8))
            .displayName(request.getBody().getDisplayName())
            .status(UserStatus.INVITED)
            .build();
    }

    public OutboxEvent buildUserInvitedOutboxEvent(InviteTokenResponse inviteTokenResponse, String email, String requestId) {
        JsonNode payloadJson = objectMapper.valueToTree(buildUserInvitedPayload(inviteTokenResponse, email));
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
