package ru.taska.transport.grpc;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.admin.v1.BlockUserRequest;
import ru.taska.api.admin.v1.BlockUserRequestBody;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequest;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequestBody;
import ru.taska.api.admin.v1.UnblockUserRequest;
import ru.taska.api.admin.v1.UnblockUserRequestBody;
import ru.taska.api.admin.v1.UserStatusResponse;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.Header;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.mapper.AdminUserManagementMapper;
import ru.taska.service.AdminUserService;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcAdminUserManagementService Unit Tests")
class GrpcAdminUserManagementServiceTest {

    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "node-1";
    private static final String TARGET_USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR_USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR_LOGIN = "admin";
    private static final String REASON = "test reason";
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.of(
            2026, 9, 2, 0, 0, 0, 0, ZoneOffset.UTC
    );

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private AdminUserManagementMapper managementMapper;

    @InjectMocks
    private GrpcAdminUserManagementService grpcAdminUserManagementService;

    private UUID targetUserId;
    private UUID actorUserId;
    private UserStatusRequestDto requestDto;
    private UserStatusResponseDto responseDto;
    private UserStatusResponse protoResponse;
    private UserCredentialStateResponseDto resetResponse;
    private UserStatusResponse resetProto;


    @BeforeEach
    void setUp() {
        targetUserId = UUID.fromString(TARGET_USER_ID);
        actorUserId = UUID.fromString(ACTOR_USER_ID);

        requestDto = UserStatusRequestDto.builder()
                .targetUserId(targetUserId)
                .actorUserId(actorUserId)
                .actorLogin(ACTOR_LOGIN)
                .reason(REASON)
                .build();

        responseDto = UserStatusResponseDto.builder()
                .userId(targetUserId)
                .previousStatus("ACTIVE")
                .currentStatus("BLOCKED")
                .changedAt(FIXED_TIME)
                .build();

        protoResponse = UserStatusResponse.newBuilder()
                .setUserId(TARGET_USER_ID)
                .setPreviousStatus(UserStatus.USER_STATUS_ACTIVE)
                .setCurrentStatus(UserStatus.USER_STATUS_BLOCKED)
                .build();

        UserCredentialStateResponseDto.CredentialState oldState = UserCredentialStateResponseDto.CredentialState.builder()
                .failedAttempts(5)
                .lockedUntil(FIXED_TIME.plusHours(1))
                .lastFailedAt(FIXED_TIME.minusMinutes(5))
                .build();

        UserCredentialStateResponseDto.CredentialState newState = UserCredentialStateResponseDto.CredentialState.empty();

        resetResponse = UserCredentialStateResponseDto.builder()
                .userId(targetUserId)
                .previousStatus("LOCKED")
                .currentStatus("ACTIVE")
                .changedAt(FIXED_TIME)
                .oldCredentialState(oldState)
                .newCredentialState(newState)
                .build();

        resetProto = UserStatusResponse.newBuilder()
                .setUserId(TARGET_USER_ID)
                .setPreviousStatus(UserStatus.USER_STATUS_LOCKED)
                .setCurrentStatus(UserStatus.USER_STATUS_ACTIVE)
                .build();
    }

    // ==================== BLOCK USER TESTS ====================

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом requestId")
        void blockUser_blankRequestId_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом nodeId")
        void blockUser_blankNodeId_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId("").build())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом targetUserId")
        void blockUser_blankTargetUserId_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId("")
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом actorUserId")
        void blockUser_blankActorUserId_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId("")
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом actorLogin")
        void blockUser_blankActorLogin_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin("")
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом reason")
        void blockUser_blankReason_fails() {
            BlockUserRequest request = BlockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason("")
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен успешно заблокировать пользователя")
        void blockUser_success() {
            BlockUserRequest grpcRequest = BlockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(BlockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            Mockito.when(managementMapper.toRequestDto(grpcRequest)).thenReturn(requestDto);
            Mockito.when(adminUserService.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(responseDto));
            Mockito.when(managementMapper.toProtoResponse(responseDto)).thenReturn(protoResponse);

            StepVerifier.create(grpcAdminUserManagementService.blockUser(Mono.just(grpcRequest)))
                    .expectNext(protoResponse)
                    .verifyComplete();

            Mockito.verify(adminUserService).blockUser(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(managementMapper).toProtoResponse(responseDto);
        }
    }

    // ==================== UNBLOCK USER TESTS ====================

    @Nested
    @DisplayName("unblockUser")
    class UnblockUserTests {

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом requestId")
        void unblockUser_blankRequestId_fails() {
            UnblockUserRequest request = UnblockUserRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                    .setBody(UnblockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.unblockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом nodeId")
        void unblockUser_blankNodeId_fails() {
            UnblockUserRequest request = UnblockUserRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId("").build())
                    .setBody(UnblockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.unblockUser(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен успешно разблокировать пользователя")
        void unblockUser_success() {
            UserStatusResponseDto unblockResponse = UserStatusResponseDto.builder()
                    .userId(targetUserId)
                    .previousStatus("BLOCKED")
                    .currentStatus("ACTIVE")
                    .changedAt(FIXED_TIME)
                    .build();

            UserStatusResponse unblockProto = UserStatusResponse.newBuilder()
                    .setUserId(TARGET_USER_ID)
                    .setPreviousStatus(UserStatus.USER_STATUS_BLOCKED)
                    .setCurrentStatus(UserStatus.USER_STATUS_ACTIVE)
                    .build();

            UnblockUserRequest grpcRequest = UnblockUserRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(UnblockUserRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            Mockito.when(managementMapper.toRequestDto(grpcRequest)).thenReturn(requestDto);
            Mockito.when(adminUserService.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(unblockResponse));
            Mockito.when(managementMapper.toProtoResponse(unblockResponse)).thenReturn(unblockProto);

            StepVerifier.create(grpcAdminUserManagementService.unblockUser(Mono.just(grpcRequest)))
                    .expectNext(unblockProto)
                    .verifyComplete();

            Mockito.verify(adminUserService).unblockUser(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(managementMapper).toProtoResponse(unblockResponse);
        }
    }

    // ==================== RESET CREDENTIAL LOCKOUT TESTS ====================

    @Nested
    @DisplayName("resetCredentialLockout")
    class ResetCredentialLockoutTests {

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом requestId")
        void resetCredentialLockout_blankRequestId_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId("").setNodeId(NODE_ID).build())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом nodeId")
        void resetCredentialLockout_blankNodeId_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId("").build())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом targetUserId")
        void resetCredentialLockout_blankTargetUserId_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId("")
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом actorUserId")
        void resetCredentialLockout_blankActorUserId_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId("")
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом actorLogin")
        void resetCredentialLockout_blankActorLogin_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin("")
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен вернуть ошибку INVALID_ARGUMENT при пустом reason")
        void resetCredentialLockout_blankReason_fails() {
            ResetCredentialLockoutRequest request = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason("")
                            .build())
                    .build();

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(request)))
                    .expectError(StatusRuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("Должен успешно сбросить credential lockout")
        void resetCredentialLockout_success() {

            ResetCredentialLockoutRequest grpcRequest = ResetCredentialLockoutRequest.newBuilder()
                    .setHeader(validHeader())
                    .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                            .setTargetUserId(TARGET_USER_ID)
                            .setActorUserId(ACTOR_USER_ID)
                            .setActorLogin(ACTOR_LOGIN)
                            .setActorRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN)
                            .setReason(REASON)
                            .build())
                    .build();

            Mockito.when(managementMapper.toRequestDto(grpcRequest)).thenReturn(requestDto);
            Mockito.when(adminUserService.resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(resetResponse));
            Mockito.when(managementMapper.toProtoResponse(resetResponse)).thenReturn(resetProto);

            StepVerifier.create(grpcAdminUserManagementService.resetCredentialLockout(Mono.just(grpcRequest)))
                    .expectNext(resetProto)
                    .verifyComplete();

            Mockito.verify(adminUserService).resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(managementMapper).toProtoResponse(resetResponse);
        }
    }

    // ==================== HELPERS ====================

    private static Header validHeader() {
        return Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }
}