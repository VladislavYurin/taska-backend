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
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.UserStatusChangeDto;
import ru.taska.service.AuditService;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

    @Mock
    private AuditService auditService;

    @Mock
    private GrpcAdminUserManagementServiceClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdminUserServiceImpl service;

    private UUID targetUserId;
    private UUID actorUserId;
    private JsonNode actorRoles;
    private UserStatusChangeDto statusChangeDto;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminUserServiceImpl(auditService, client, objectMapper);
        targetUserId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        actorRoles = objectMapper.readTree("[\"GLOBAL_ADMIN\"]");
        statusChangeDto = new UserStatusChangeDto(
                targetUserId,
                "USER_STATUS_ACTIVE",
                "USER_STATUS_BLOCKED",
                OffsetDateTime.of(2026, 8, 22, 7, 0, 0, 0, ZoneOffset.UTC)
        );
    }

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("Должен вызвать auth-service и записать audit USER_BLOCKED")
        void shouldCallAuthAndWriteAudit() {
            Mockito.when(client.blockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID))
                    .thenReturn(Mono.just(statusChangeDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.blockUser(
                            targetUserId, actorUserId, ACTOR_LOGIN, actorRoles, REASON, REQUEST_ID, NODE_ID))
                    .expectNext(statusChangeDto)
                    .verifyComplete();

            ArgumentCaptor<AuditEventDto> auditCaptor = ArgumentCaptor.forClass(AuditEventDto.class);
            Mockito.verify(client).blockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID);
            Mockito.verify(auditService).logAudit(auditCaptor.capture());

            AuditEventDto auditEvent = auditCaptor.getValue();
            Assertions.assertThat(auditEvent.getRequestId()).isEqualTo(REQUEST_ID);
            Assertions.assertThat(auditEvent.getActorUserId()).isEqualTo(actorUserId);
            Assertions.assertThat(auditEvent.getActorLogin()).isEqualTo(ACTOR_LOGIN);
            Assertions.assertThat(auditEvent.getActorRoles()).isEqualTo(actorRoles);
            Assertions.assertThat(auditEvent.getAction()).isEqualTo("USER_BLOCKED");
            Assertions.assertThat(auditEvent.getTargetService()).isEqualTo("auth-service");
            Assertions.assertThat(auditEvent.getTargetTable()).isEqualTo("users");
            Assertions.assertThat(auditEvent.getTargetId()).isEqualTo(targetUserId.toString());
            Assertions.assertThat(auditEvent.getOldValue().get("status").asString())
                    .isEqualTo("USER_STATUS_ACTIVE");
            Assertions.assertThat(auditEvent.getNewValue().get("status").asString())
                    .isEqualTo("USER_STATUS_BLOCKED");
            Assertions.assertThat(auditEvent.getReason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("Должен пробросить ошибку audit после успешного block в auth-service")
        void shouldPropagateAuditErrorAfterSuccessfulBlock() {
            RuntimeException auditError = new RuntimeException("audit failed");

            Mockito.when(client.blockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID))
                    .thenReturn(Mono.just(statusChangeDto));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.error(auditError));

            StepVerifier.create(service.blockUser(
                            targetUserId, actorUserId, ACTOR_LOGIN, actorRoles, REASON, REQUEST_ID, NODE_ID))
                    .expectErrorMatches(error -> error == auditError)
                    .verify();

            Mockito.verify(client).blockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID);
            Mockito.verify(auditService).logAudit(ArgumentMatchers.any(AuditEventDto.class));
        }
    }

    @Nested
    @DisplayName("unblockUser")
    class UnblockUserTests {

        @Test
        @DisplayName("Должен вызвать auth-service и записать audit USER_UNBLOCKED")
        void shouldCallAuthAndWriteAudit() {
            UserStatusChangeDto unblockResult = new UserStatusChangeDto(
                    targetUserId,
                    "USER_STATUS_BLOCKED",
                    "USER_STATUS_ACTIVE",
                    OffsetDateTime.of(2026, 8, 22, 7, 0, 0, 0, ZoneOffset.UTC)
            );

            Mockito.when(client.unblockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID))
                    .thenReturn(Mono.just(unblockResult));
            Mockito.when(auditService.logAudit(ArgumentMatchers.any(AuditEventDto.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.unblockUser(
                            targetUserId, actorUserId, ACTOR_LOGIN, actorRoles, REASON, REQUEST_ID, NODE_ID))
                    .expectNext(unblockResult)
                    .verifyComplete();

            ArgumentCaptor<AuditEventDto> auditCaptor = ArgumentCaptor.forClass(AuditEventDto.class);
            Mockito.verify(client).unblockUser(targetUserId, actorUserId, REASON, REQUEST_ID, NODE_ID);
            Mockito.verify(auditService).logAudit(auditCaptor.capture());
            Assertions.assertThat(auditCaptor.getValue().getAction()).isEqualTo("USER_UNBLOCKED");
        }
    }
}
