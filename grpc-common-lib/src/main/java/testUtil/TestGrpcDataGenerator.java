package testUtil;

import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequestBody;
import ru.taska.api.common.v1.Header;

public class TestGrpcDataGenerator {

    public static InviteUserRequestBody getAdminCreateUserRequestBody() {
        return InviteUserRequestBody.newBuilder()
                                  .setEmail(TestConstantHolder.TEST_USER_EMAIL)
                                  .setDisplayName(TestConstantHolder.TEST_USER_DISPLAY_NAME)
                                  .build();
    }

    public static Header getRequestHeader() {
        return Header.newBuilder()
                     .setRequestId(TestConstantHolder.HEADER_REQUEST_ID)
                     .setNodeId(TestConstantHolder.HEADER_NODE_ID)
                     .build();
    }

    public static InviteUserRequest getAdminCreateUserRequest() {
        return InviteUserRequest.newBuilder()
                                     .setHeader(getRequestHeader())
                                     .setBody(getAdminCreateUserRequestBody())
                                     .build();
    }
}
