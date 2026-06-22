package testUtil;

import java.time.Instant;
import java.util.UUID;

public class TestConstantHolder {
    public static final String HEADER_REQUEST_ID = "header_rqId";
    public static final String HEADER_NODE_ID = "header_nodeId";
    public static final String TEST_USER_EMAIL = "test@test.com";
    public static final String TEST_USER_LOGIN = "test";
    public static final String TEST_USER_DISPLAY_NAME = "Test User";
    public static final String EMPTY_STRING = "";
    public static final UUID TEST_USER_ID = UUID.randomUUID();
    public static final String RAW_INVITE_TOKEN = "raw-invite-token";
    public static final Instant INVITE_TOKEN_EXPIRES_AT = Instant.now().plusSeconds(3600);
}
