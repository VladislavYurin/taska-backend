package testUtil;

import ru.taska.api.auth.admin.inviteuser.v1.AdminCreateUserBody;
import ru.taska.api.auth.admin.inviteuser.v1.AdminCreateUserRequest;
import ru.taska.api.common.v1.Header;

public class TestGrpcDataGenerator {

    public static AdminCreateUserBody getAdminCreateUserRequestBody() {
        return AdminCreateUserBody.newBuilder()
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

    public static AdminCreateUserRequest getAdminCreateUserRequest() {
        return AdminCreateUserRequest.newBuilder()
                                     .setHeader(getRequestHeader())
                                     .setBody(getAdminCreateUserRequestBody())
                                     .build();
    }
}
