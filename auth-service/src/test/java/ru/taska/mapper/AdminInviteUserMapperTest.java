package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.dto.InviteTokenResponse;
import testUtil.TestConstantHolder;
import testUtil.TestGrpcDataGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AdminInviteUserMapperTest {

    private static final String REQUEST_ID = "test-request-id";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AdminInviteUserMapper mapper = new AdminInviteUserMapper(objectMapper);

    @Test
    void buildInvitedUserFromRequest() {
        InviteUserRequest request = TestGrpcDataGenerator.getInviteUserRequest();
        User user = mapper.buildInvitedUserFromRequest(request);
        Assertions.assertThat(user.getEmail()).isEqualTo(TestConstantHolder.TEST_USER_EMAIL);
        Assertions.assertThat(user.getLogin()).isNotNull();
        Assertions.assertThat(user.getDisplayName()).isEqualTo(TestConstantHolder.TEST_USER_DISPLAY_NAME);
        Assertions.assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);
    }

    @Test
    void buildUserInvitedOutboxEvent() {
        InviteTokenResponse tokenResponse = InviteTokenResponse.builder()
                                                               .userId(TestConstantHolder.TEST_USER_ID)
                                                               .rawToken(TestConstantHolder.RAW_INVITE_TOKEN)
                                                               .expiresAt(TestConstantHolder.INVITE_TOKEN_EXPIRES_AT)
                                                               .build();
        OutboxEvent event = mapper.buildUserInvitedOutboxEvent(tokenResponse, TestConstantHolder.TEST_USER_EMAIL, REQUEST_ID);
        Assertions.assertThat(event.getAggregateType()).isEqualTo("user");
        Assertions.assertThat(event.getAggregateId()).isEqualTo(TestConstantHolder.TEST_USER_ID);
        Assertions.assertThat(event.getEventType()).isEqualTo("UserInvited");
        Assertions.assertThat(event.getAttempts()).isZero();

        JsonNode payload = event.getPayload();
        Assertions.assertThat(payload).isNotNull();
        Assertions.assertThat(payload.get("userId").asString()).isEqualTo(TestConstantHolder.TEST_USER_ID.toString());
        Assertions.assertThat(payload.get("email").asString()).isEqualTo(TestConstantHolder.TEST_USER_EMAIL);
        Assertions.assertThat(payload.get("rawInviteToken").asString()).isEqualTo(TestConstantHolder.RAW_INVITE_TOKEN);
        Assertions.assertThat(payload.get("expiresAt")).isNotNull();
    }
}