package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.api.directory.v1.ReactorUserDirectoryServiceGrpc;
import ru.taska.api.directory.v1.SearchUsersRequest;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class GrpcUserDirectoryClient {
    private final ReactorAdminInviteUserServiceGrpc.ReactorAdminInviteUserServiceStub invitations;
    private final ReactorUserDirectoryServiceGrpc.ReactorUserDirectoryServiceStub directory;
    private final GrpcClientProperties properties;

    public record InvitationResult(boolean created, InvitationResponseDto body) { }

    public Mono<InvitationResult> invite(InvitationRequestDto body, GatewayContext context) {
        var request = InviteUserRequest.newBuilder().setHeader(header(context))
                .setBody(InviteUserRequestBody.newBuilder().setActorUserId(context.userContext().userId())
                        .setEmail(body.getEmail()).setDisplayName(body.getDisplayName() == null ? "" : body.getDisplayName())
                        .setResend(Boolean.TRUE.equals(body.getResend()))).build();
        return invitations.withDeadlineAfter(properties.authService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS)
                .inviteUser(request).map(result -> {
                    var dto = new InvitationResponseDto();
                    dto.setUserId(UUID.fromString(result.getUserId()));
                    dto.setStatus(UserStatusDto.INVITED);
                    dto.setDeliveryQueued(result.getDeliveryQueued());
                    dto.setExpiresAt(OffsetDateTime.parse(result.getExpiresAt()));
                    return new InvitationResult(result.getCreated(), dto);
                });
    }

    public Mono<UserDirectoryResponseDto> search(String query, int page, int pageSize, GatewayContext context) {
        var request = SearchUsersRequest.newBuilder().setHeader(header(context))
                .setActorUserId(context.userContext().userId()).setQuery(query).setPage(page).setPageSize(pageSize).build();
        return directory.withDeadlineAfter(properties.authService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS)
                .searchUsers(request).map(result -> {
                    var dto = new UserDirectoryResponseDto();
                    dto.setHasMore(result.getHasMore());
                    dto.setItems(result.getItemsList().stream().map(row -> {
                        var item = new DirectoryUserDto();
                        item.setId(UUID.fromString(row.getId()));
                        item.setDisplayName(row.getDisplayName());
                        item.setEmail(row.getEmail());
                        item.setAvatarUrl(row.hasAvatarUrl() ? java.net.URI.create(row.getAvatarUrl()) : null);
                        return item;
                    }).toList());
                    return dto;
                });
    }

    private Header header(GatewayContext context) {
        return ru.taska.mapper.MappingUtils.buildGrpcHeader(context);
    }
}
