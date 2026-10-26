package ru.taska.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.UserDisplayNameChangeDto;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserDisplayNameAdminServiceTest {
    private final GrpcAdminUserManagementServiceClient client = mock(GrpcAdminUserManagementServiceClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final UserDisplayNameAdminService service = new UserDisplayNameAdminService(client, audit, JsonMapper.builder().build());
    private final UserStatusRequestDto actor = UserStatusRequestDto.builder().targetUserId(UUID.randomUUID())
            .actorUserId(UUID.randomUUID()).actorDisplayName("Administrator").actorEmail("admin@example.test")
            .role(GlobalRole.GLOBAL_ADMIN).build();

    @Test void recordsPreviousAndNewNamesAndAuthenticatedActor() {
        var change = new UserDisplayNameChangeDto(UUID.randomUUID(), "Previous", "Current");
        when(client.updateUserDisplayName("req", "node", actor, "Current")).thenReturn(Mono.just(change));
        when(audit.logAudit(any())).thenReturn(Mono.empty());
        assertThat(service.update("req", "node", actor, "Current").block()).isEqualTo(change);
        var capture = ArgumentCaptor.forClass(AuditEventDto.class);
        verify(audit).logAudit(capture.capture());
        var event = capture.getValue();
        assertThat(event.getAction()).isEqualTo("USER_DISPLAY_NAME_UPDATED");
        assertThat(event.getActorUserId()).isEqualTo(actor.actorUserId());
        assertThat(event.getActorEmail()).isEqualTo(actor.actorEmail());
        assertThat(event.getTargetId()).isEqualTo(change.userId().toString());
        assertThat(event.getTargetService()).isEqualTo("auth");
        assertThat(event.getOldValue().get("displayName").asString()).isEqualTo("Previous");
        assertThat(event.getNewValue().get("displayName").asString()).isEqualTo("Current");
        assertThat(event.getReason()).isNotBlank();
    }
    @Test void failedMutationDoesNotWriteAuditAndAuditFailureIsNotReportedAsSuccess() {
        when(client.updateUserDisplayName(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("auth unavailable")));
        assertThatThrownBy(() -> service.update("req", "node", actor, "Current").block()).hasMessage("auth unavailable");
        verifyNoInteractions(audit);
        when(client.updateUserDisplayName(any(), any(), any(), any())).thenReturn(Mono.just(new UserDisplayNameChangeDto(actor.targetUserId(), "Previous", "Current")));
        when(audit.logAudit(any())).thenReturn(Mono.error(new IllegalStateException("audit unavailable")));
        assertThatThrownBy(() -> service.update("req", "node", actor, "Current").block()).hasMessage("audit unavailable");
    }
}
