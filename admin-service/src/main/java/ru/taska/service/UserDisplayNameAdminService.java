package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.UserDisplayNameChangeDto;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class UserDisplayNameAdminService {
    private final GrpcAdminUserManagementServiceClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public Mono<UserDisplayNameChangeDto> update(String requestId, String nodeId, UserStatusRequestDto actor, String displayName) {
        return client.updateUserDisplayName(requestId, nodeId, actor, displayName)
                .flatMap(change -> audit.logAudit(AuditEventDto.builder().requestId(requestId)
                        .actorUserId(actor.actorUserId()).actorDisplayName(actor.actorDisplayName()).actorEmail(actor.actorEmail())
                        .actorRoles(mapper.valueToTree(actor.role())).action(ru.taska.config.props.AuditAction.USER_DISPLAY_NAME_UPDATED.name())
                        .targetService("auth").targetTable("users").targetId(change.userId().toString())
                        .oldValue(mapper.createObjectNode().put("displayName", change.previousDisplayName()))
                        .newValue(mapper.createObjectNode().put("displayName", change.displayName()))
                        .reason("Display name updated by administrator").build()).thenReturn(change));
    }
}
