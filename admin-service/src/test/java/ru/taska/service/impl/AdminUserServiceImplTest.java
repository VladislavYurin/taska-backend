package ru.taska.service.impl;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.service.AuditService;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserServiceImpl Unit Tests")
class AdminUserServiceImplTest {

    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "node-1";
    private static final String ACTOR_LOGIN = "admin";
    private static final String REASON = "policy violation";
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.of(
            2026, 9, 2, 0, 0, 0, 0, ZoneOffset.UTC
    );

    @Mock
    private AuditService auditService;

    @Mock
    private GrpcAdminUserManagementServiceClient client;

    @Mock
    private GrpcClientProperties grpcClientProperties;

    @Mock
    private GrpcClientProperties.Service authService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminUserServiceImpl service;

    private UUID targetUserId;
    private UUID actorUserId;
    private UserStatusRequestDto requestDto;
    private UserStatusResponseDto responseDto;
    private UserCredentialStateResponseDto credentialStateResponseDto;

    @BeforeEach
    void setUp() {
        service = new AdminUserServiceImpl(
                auditService,
                client,
                objectMapper,
                grpcClientProperties
        );

        targetUserId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();

        requestDto = UserStatusRequestDto.builder()
                .targetUserId(targetUserId)
                .actorUserId(actorUserId)
                .actorLogin(ACTOR_LOGIN)
                .role(GlobalRole.GLOBAL_ADMIN)
                .reason(REASON)
                .build();

        responseDto = UserStatusResponseDto.builder()
                .userId(targetUserId)
                .previousStatus("ACTIVE")
                .currentStatus("BLOCKED")
                .changedAt(FIXED_TIME)
                .build();

        UserCredentialStateResponseDto.CredentialState oldState = UserCredentialStateResponseDto.CredentialState.builder()
                .failedAttempts(5)
                .lockedUntil(FIXED_TIME.plusHours(1))
                .lastFailedAt(FIXED_TIME.minusMinutes(5))
                .build();

        UserCredentialStateResponseDto.CredentialState newState = UserCredentialStateResponseDto.CredentialState.empty();

        credentialStateResponseDto = UserCredentialStateResponseDto.builder()
                .userId(targetUserId)
                .previousStatus("LOCKED")
                .currentStatus("ACTIVE")
                .changedAt(FIXED_TIME)
                .oldCredentialState(oldState)
                .newCredentialState(newState)
                .build();

        Mockito.lenient().when(grpcClientProperties.authService())
                .thenReturn(authService);
        Mockito.lenient().when(authService.serviceName())
                .thenReturn("auth-service");
    }

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("Должен вызвать auth-service и записать audit USER_BLOCKED")
        void shouldCallAuthAndWriteAudit() {
            Mockito.when(client.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(responseDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectNext(responseDto)
                    .verifyComplete();

            ArgumentCaptor<AuditEventDto> auditCaptor = ArgumentCaptor.forClass(AuditEventDto.class);
            Mockito.verify(client).blockUser(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(auditService).logAudit(auditCaptor.capture());

            AuditEventDto auditEvent = auditCaptor.getValue();
            Assertions.assertThat(auditEvent.getRequestId()).isEqualTo(REQUEST_ID);
            Assertions.assertThat(auditEvent.getActorUserId()).isEqualTo(actorUserId);
            Assertions.assertThat(auditEvent.getActorLogin()).isEqualTo(ACTOR_LOGIN);
            Assertions.assertThat(auditEvent.getActorRoles()).isNotNull();
            Assertions.assertThat(auditEvent.getAction()).isEqualTo("USER_BLOCKED");
            Assertions.assertThat(auditEvent.getTargetService()).isEqualTo("auth-service");
            Assertions.assertThat(auditEvent.getTargetTable()).isEqualTo("users");
            Assertions.assertThat(auditEvent.getTargetId()).isEqualTo(targetUserId.toString());
            Assertions.assertThat(auditEvent.getOldValue().get("status").asString())
                    .isEqualTo("ACTIVE");
            Assertions.assertThat(auditEvent.getNewValue().get("status").asString())
                    .isEqualTo("BLOCKED");
            Assertions.assertThat(auditEvent.getReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("Должен пробросить ошибку audit после успешного block в auth-service")
        void shouldPropagateAuditErrorAfterSuccessfulBlock() {
            RuntimeException auditError = new RuntimeException("audit failed");

            Mockito.when(client.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(responseDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.error(auditError));

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error == auditError)
                    .verify();

            Mockito.verify(client).blockUser(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(auditService).logAudit(ArgumentMatchers.any(AuditEventDto.class));
        }
    }

    @Nested
    @DisplayName("unblockUser")
    class UnblockUserTests {

        @Test
        @DisplayName("Должен вызвать auth-service и записать audit USER_UNBLOCKED")
        void shouldCallAuthAndWriteAudit() {
            UserStatusResponseDto unblockResponse = UserStatusResponseDto.builder()
                    .userId(targetUserId)
                    .previousStatus("BLOCKED")
                    .currentStatus("ACTIVE")
                    .changedAt(FIXED_TIME)
                    .build();

            Mockito.when(client.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(unblockResponse));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectNext(unblockResponse)
                    .verifyComplete();

            ArgumentCaptor<AuditEventDto> auditCaptor = ArgumentCaptor.forClass(AuditEventDto.class);
            Mockito.verify(client).unblockUser(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(auditService).logAudit(auditCaptor.capture());
            Assertions.assertThat(auditCaptor.getValue().getAction()).isEqualTo("USER_UNBLOCKED");
            Assertions.assertThat(auditCaptor.getValue().getOldValue().get("status").asString())
                    .isEqualTo("BLOCKED");
            Assertions.assertThat(auditCaptor.getValue().getNewValue().get("status").asString())
                    .isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("resetCredentialLockout")
    class ResetCredentialLockoutTests {

        @Test
        @DisplayName("Должен вызвать auth-service и записать audit RESET_CREDENTIAL_LOCKOUT")
        void shouldCallAuthAndWriteAudit() {
            Mockito.when(client.resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(credentialStateResponseDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto))
                    .expectNext(credentialStateResponseDto)
                    .verifyComplete();

            ArgumentCaptor<AuditEventDto> auditCaptor = ArgumentCaptor.forClass(AuditEventDto.class);
            Mockito.verify(client).resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(auditService).logAudit(auditCaptor.capture());

            AuditEventDto auditEvent = auditCaptor.getValue();
            Assertions.assertThat(auditEvent.getAction()).isEqualTo("RESET_CREDENTIAL_LOCKOUT");
            Assertions.assertThat(auditEvent.getTargetTable()).isEqualTo("credentials");

            // Проверяем oldValue
            ObjectNode oldValue = (ObjectNode) auditEvent.getOldValue();
            Assertions.assertThat(oldValue.get("status").asString()).isEqualTo("LOCKED");
            Assertions.assertThat(oldValue.get("failedAttempts").asInt()).isEqualTo(5);
            Assertions.assertThat(oldValue.get("lockedUntil").asString()).isNotEmpty();
            Assertions.assertThat(oldValue.get("lastFailedAt").asString()).isNotEmpty();

            // Проверяем newValue
            ObjectNode newValue = (ObjectNode) auditEvent.getNewValue();
            Assertions.assertThat(newValue.get("status").asString()).isEqualTo("ACTIVE");
            Assertions.assertThat(newValue.get("failedAttempts").asInt()).isEqualTo(0);
            Assertions.assertThat(newValue.get("lockedUntil").asString()).isEmpty();
            Assertions.assertThat(newValue.get("lastFailedAt").asString()).isEmpty();
        }

        @Test
        @DisplayName("Должен пробросить ошибку audit после успешного reset в auth-service")
        void shouldPropagateAuditErrorAfterSuccessfulReset() {
            RuntimeException auditError = new RuntimeException("audit failed");

            Mockito.when(client.resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto))
                    .thenReturn(Mono.just(credentialStateResponseDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.error(auditError));

            StepVerifier.create(service.resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error == auditError)
                    .verify();

            Mockito.verify(client).resetCredentialLockout(REQUEST_ID, NODE_ID, requestDto);
            Mockito.verify(auditService).logAudit(ArgumentMatchers.any(AuditEventDto.class));
        }
    }
}
