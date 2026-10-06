package ru.taska.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.GlobalRoleTypeDto;
import ru.taska.domain.dto.LoginRequestDto;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.PasswordByTokenRequestDto;
import ru.taska.domain.dto.RefreshRequestDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthMapperTest {

    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "api-gateway-1";

    private final AuthMapper authMapper = new AuthMapper();

    private GatewayContext gatewayContext() {
        return new GatewayContext(REQUEST_ID, NODE_ID, null);
    }

    // ============================================================
    // toGatewayUserStatus
    // ============================================================

    @Nested
    @DisplayName("toGatewayUserStatus")
    class ToGatewayUserStatus {

        @Test
        void shouldMapActive() {
            assertThat(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_ACTIVE))
                    .isEqualTo(GatewayUserStatus.ACTIVE);
        }

        @Test
        void shouldMapInvited() {
            assertThat(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_INVITED))
                    .isEqualTo(GatewayUserStatus.INVITED);
        }

        @Test
        void shouldMapBlocked() {
            assertThat(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_BLOCKED))
                    .isEqualTo(GatewayUserStatus.BLOCKED);
        }

        @Test
        void shouldMapLocked() {
            assertThat(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_LOCKED))
                    .isEqualTo(GatewayUserStatus.LOCKED);
        }

        @Test
        void shouldMapUnspecified() {
            assertThat(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_UNSPECIFIED))
                    .isEqualTo(GatewayUserStatus.UNSPECIFIED);
        }

        @Test
        void shouldThrowDomainExceptionForUnknownStatus() {
            assertThatThrownBy(() -> authMapper.toGatewayUserStatus(UserStatus.UNRECOGNIZED))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("Unknown UserStatus")
                    .satisfies(ex -> assertThat(((DomainException) ex).getStatus())
                            .isEqualTo(DomainStatus.INVALID_ARGUMENT));
        }
    }

    // ============================================================
    // toGlobalRole
    // ============================================================

    @Nested
    @DisplayName("toGlobalRole")
    class ToGlobalRole {

        @Test
        void shouldMapGlobalAdmin() {
            assertThat(authMapper.toGlobalRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN))
                    .isEqualTo(GlobalRole.GLOBAL_ADMIN);
        }

        @Test
        void shouldMapUser() {
            assertThat(authMapper.toGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER))
                    .isEqualTo(GlobalRole.USER);
        }

        @Test
        void shouldMapUnspecified() {
            assertThat(authMapper.toGlobalRole(GlobalRoleProto.GLOBAL_ROLE_UNSPECIFIED))
                    .isEqualTo(GlobalRole.UNSPECIFIED);
        }

        @Test
        void shouldThrowDomainExceptionForUnknownRole() {
            assertThatThrownBy(() -> authMapper.toGlobalRole(GlobalRoleProto.UNRECOGNIZED))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining("Unknown GlobalRole")
                    .satisfies(ex -> assertThat(((DomainException) ex).getStatus())
                            .isEqualTo(DomainStatus.INVALID_ARGUMENT));
        }
    }

    // ============================================================
    // toValidateAccessTokenRestResponse
    // ============================================================

    @Nested
    @DisplayName("toValidateAccessTokenRestResponse")
    class ToValidateAccessTokenRestResponse {

        @Test
        void shouldMapAllFieldsForLockedUser() {
            GatewayUserContext context = GatewayUserContext.builder()
                    .userId("user-1")
                    .login("john.doe")
                    .email("john.doe@example.com")
                    .displayName("John Doe")
                    .status(GatewayUserStatus.LOCKED)
                    .globalRole(GlobalRole.USER)
                    .build();

            ValidateAccessTokenResponseDto dto =
                    authMapper.toValidateAccessTokenRestResponse(context);

            assertThat(dto.getId()).isEqualTo("user-1");
            assertThat(dto.getLogin()).isEqualTo("john.doe");
            assertThat(dto.getEmail()).isEqualTo("john.doe@example.com");
            assertThat(dto.getDisplayName()).isEqualTo("John Doe");
            assertThat(dto.getStatus()).isEqualTo("LOCKED");
            assertThat(dto.getGlobalRole()).isEqualTo(GlobalRoleTypeDto.USER);
        }

        @Test
        void shouldMapAllFieldsForActiveGlobalAdmin() {
            GatewayUserContext context = GatewayUserContext.builder()
                    .userId("admin-1")
                    .login("admin")
                    .email("admin@example.com")
                    .displayName("Admin")
                    .status(GatewayUserStatus.ACTIVE)
                    .globalRole(GlobalRole.GLOBAL_ADMIN)
                    .build();

            ValidateAccessTokenResponseDto dto =
                    authMapper.toValidateAccessTokenRestResponse(context);

            assertThat(dto.getStatus()).isEqualTo("ACTIVE");
            assertThat(dto.getGlobalRole()).isEqualTo(GlobalRoleTypeDto.GLOBAL_ADMIN);
        }

        @Test
        void shouldReturnNullStatusWhenContextStatusIsNull() {
            GatewayUserContext context = GatewayUserContext.builder()
                    .userId("user-1")
                    .login("john.doe")
                    .email("john.doe@example.com")
                    .displayName("John Doe")
                    .status(null)
                    .globalRole(GlobalRole.USER)
                    .build();

            ValidateAccessTokenResponseDto dto =
                    authMapper.toValidateAccessTokenRestResponse(context);

            assertThat(dto.getStatus()).isNull();
            assertThat(dto.getGlobalRole()).isEqualTo(GlobalRoleTypeDto.USER);
        }

        @Test
        void shouldFallbackToUnspecifiedWhenGlobalRoleIsNull() {
            GatewayUserContext context = GatewayUserContext.builder()
                    .userId("user-1")
                    .login("john.doe")
                    .email("john.doe@example.com")
                    .displayName("John Doe")
                    .status(GatewayUserStatus.ACTIVE)
                    .globalRole(null)
                    .build();

            ValidateAccessTokenResponseDto dto =
                    authMapper.toValidateAccessTokenRestResponse(context);

            assertThat(dto.getGlobalRole()).isEqualTo(GlobalRoleTypeDto.UNSPECIFIED);
        }
    }

    // ============================================================
    // gRPC request mappers
    // ============================================================

    @Nested
    @DisplayName("gRPC request mappers")
    class GrpcRequestMappers {

        @Test
        void toLoginGrpcRequest_shouldFillHeaderAndBody() {
            LoginRequestDto source = new LoginRequestDto();
            source.setEmail("john.doe@example.com");
            source.setPassword("secret");

            LoginRequest request = authMapper.toLoginGrpcRequest(source, gatewayContext());

            assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
            assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
            assertThat(request.getBody().getEmail()).isEqualTo("john.doe@example.com");
            assertThat(request.getBody().getPassword()).isEqualTo("secret");
        }

        @Test
        void toRefreshGrpcRequest_shouldFillHeaderAndBody() {
            RefreshRequestDto source = new RefreshRequestDto();
            source.setRefreshToken("refresh-token");

            RefreshRequest request = authMapper.toRefreshGrpcRequest(source, gatewayContext());

            assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
            assertThat(request.getBody().getRefreshToken()).isEqualTo("refresh-token");
        }

        @Test
        void toPasswordByTokenGrpcRequest_shouldFillHeaderAndBody() {
            PasswordByTokenRequestDto source = new PasswordByTokenRequestDto();
            source.setToken("invite-token");
            source.setNewPassword("new-password");

            SetPasswordByTokenRequest request =
                    authMapper.toPasswordByTokenGrpcRequest(source, gatewayContext());

            assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
            assertThat(request.getBody().getToken()).isEqualTo("invite-token");
            assertThat(request.getBody().getNewPassword()).isEqualTo("new-password");
        }
    }

    // ============================================================
    // gRPC response mappers
    // ============================================================

    @Nested
    @DisplayName("gRPC response mappers")
    class GrpcResponseMappers {

        @Test
        void toLoginRestResponse_shouldMapAllFields() {
            LoginResponse source = LoginResponse.newBuilder()
                    .setAccessToken("access")
                    .setRefreshToken("refresh")
                    .setExpiresIn(3600L)
                    .build();

            LoginResponseDto dto = authMapper.toLoginRestResponse(source);

            assertThat(dto.getAccessToken()).isEqualTo("access");
            assertThat(dto.getRefreshToken()).isEqualTo("refresh");
            assertThat(dto.getExpiresIn()).isEqualTo(3600L);
        }

        @Test
        void toRefreshRestResponse_shouldMapAllFields() {
            RefreshResponse source = RefreshResponse.newBuilder()
                    .setAccessToken("access")
                    .setRefreshToken("refresh")
                    .setExpiresIn(3600L)
                    .build();

            RefreshResponseDto dto = authMapper.toRefreshRestResponse(source);

            assertThat(dto.getAccessToken()).isEqualTo("access");
            assertThat(dto.getRefreshToken()).isEqualTo("refresh");
            assertThat(dto.getExpiresIn()).isEqualTo(3600L);
        }
    }
}