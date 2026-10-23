package ru.taska.transport.grpc;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.*;
import ru.taska.api.directory.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.InvitationRequestDto;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GrpcUserDirectoryClientTest {
    @Test
    void bothRequestsPropagateVerifiedActorTraceAndBusinessParameters() {
        var invitations = mock(ReactorAdminInviteUserServiceGrpc.ReactorAdminInviteUserServiceStub.class);
        var directory = mock(ReactorUserDirectoryServiceGrpc.ReactorUserDirectoryServiceStub.class);
        var config = mock(GrpcClientProperties.class);
        when(config.authService()).thenReturn(new GrpcClientProperties.Service("localhost", 9091, Duration.ofSeconds(5)));
        when(invitations.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(invitations);
        when(directory.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(directory);
        var client = new GrpcUserDirectoryClient(invitations, directory, config);
        String actor = UUID.randomUUID().toString();
        String invited = UUID.randomUUID().toString();
        var context = new GatewayContext("request-204", "gateway", GatewayUserContext.builder().userId(actor).build());
        when(invitations.inviteUser(any(InviteUserRequest.class))).thenReturn(Mono.just(InviteUserResponse.newBuilder()
                .setUserId(invited).setCreated(true).setDeliveryQueued(true).setExpiresAt("2027-01-01T00:00:00Z").build()));
        var body = new InvitationRequestDto();
        body.setEmail("person@example.test");
        body.setResend(true);
        var result = client.invite(body, context).block();
        assertThat(result.created()).isTrue();
        assertThat(result.body().getDeliveryQueued()).isTrue();
        var inviteCaptor = ArgumentCaptor.forClass(InviteUserRequest.class);
        verify(invitations).inviteUser(inviteCaptor.capture());
        assertThat(inviteCaptor.getValue().getHeader().getRequestId()).isEqualTo("request-204");
        assertThat(inviteCaptor.getValue().getBody().getActorUserId()).isEqualTo(actor);
        assertThat(inviteCaptor.getValue().getBody().getResend()).isTrue();
        assertThat(inviteCaptor.getValue().getBody().getDisplayName()).isEmpty();

        when(directory.searchUsers(any(SearchUsersRequest.class))).thenReturn(Mono.just(SearchUsersResponse.newBuilder()
                .setHasMore(true).addItems(DirectoryUser.newBuilder().setId(invited).setDisplayName("Person")
                        .setEmail("person@example.test").setAvatarUrl("https://storage.example.test/avatar")).build()));
        var page = client.search("Person", 2, 5, context).block();
        assertThat(page.getHasMore()).isTrue();
        assertThat(page.getItems().getFirst().getAvatarUrl().toString()).isEqualTo("https://storage.example.test/avatar");
        var searchCaptor = ArgumentCaptor.forClass(SearchUsersRequest.class);
        verify(directory).searchUsers(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getActorUserId()).isEqualTo(actor);
        assertThat(searchCaptor.getValue().getHeader().getNodeId()).isEqualTo("gateway");
        assertThat(searchCaptor.getValue().getQuery()).isEqualTo("Person");
        assertThat(searchCaptor.getValue().getPage()).isEqualTo(2);
        assertThat(searchCaptor.getValue().getPageSize()).isEqualTo(5);
    }
}
