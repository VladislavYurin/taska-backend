package ru.taska.security;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.*;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.mapper.*;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.taska.security.McpMarkerVerifierTest.*;

class ActionAttributionGatewayTest {
    private static final UUID TOKEN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final URI ISSUE = URI.create("http://localhost/api/v1/issues/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void authenticatedContextDeterminesChannelAndOnlyInternalHeaderCarriesTokenId() {
        for (AccessTokenKind kind : List.of(AccessTokenKind.ACCESS_TOKEN_KIND_JWT, AccessTokenKind.ACCESS_TOKEN_KIND_PAT)) {
            var request = MockServerHttpRequest.get(ISSUE.toString()).header("Authorization", "Bearer " + BEARER).build();
            var context = factory(kind).buildContext(RID, MockServerWebExchange.from(request), EndpointSecurity.PROTECTED).block();
            var header = MappingUtils.buildGrpcHeader(context);
            assertThat(header.getActionChannel()).isEqualTo(kind == AccessTokenKind.ACCESS_TOKEN_KIND_PAT ? "API_TOKEN" : "UI");
            assertThat(header.hasTokenId()).isEqualTo(kind == AccessTokenKind.ACCESS_TOKEN_KIND_PAT);
        }
        var request = signed("GET", ISSUE, RID, BEARER, NOW, nonce(9));
        var context = factory(AccessTokenKind.ACCESS_TOKEN_KIND_PAT).buildContext(RID, MockServerWebExchange.from(request), EndpointSecurity.PROTECTED).block();
        assertThat(context.channel()).isEqualTo(ActionChannel.MCP);
        assertThat(MappingUtils.buildGrpcHeader(context).getTokenId()).isEqualTo(TOKEN_ID.toString());
        denied(() -> factory(AccessTokenKind.ACCESS_TOKEN_KIND_JWT).buildContext(RID,
                MockServerWebExchange.from(signed("GET", ISSUE, RID, BEARER, NOW, nonce(8))), EndpointSecurity.PROTECTED).block());
    }

    @Test
    void publicHistoryAndCommentDtosExposeOnlyChannelAndLegacyRowsStayNullable() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var profiles = mock(UserProfileMapper.class);
        var issues = new IssueMapper(json, profiles, mock(IssueAttachmentMapper.class), mock(IssueWatcherMapper.class));
        var comments = new CommentRestMapper(profiles);
        for (String channel : new String[]{null, "UI", "API_TOKEN", "MCP"}) {
            var history = ru.taska.api.issue.v1.IssueHistoryResponse.newBuilder().setId("history").setPayload("{}");
            var comment = ru.taska.api.issue.v1.IssueCommentResponse.newBuilder()
                    .setId(UUID.randomUUID().toString()).setIssueId(UUID.randomUUID().toString())
                    .setProjectId(UUID.randomUUID().toString()).setAuthorUserId(UUID.randomUUID().toString());
            if (channel != null) { history.setChannel(channel); comment.setChannel(channel); }
            var historyDto = issues.toRestIssueHistoryResponse(history.build());
            var commentDto = comments.toCommentResponseDto(comment.build());
            assertThat(historyDto.getChannel()).isEqualTo(channel);
            assertThat(commentDto.getChannel()).isEqualTo(channel);
            String body = json.writeValueAsString(List.of(historyDto, commentDto));
            assertThat(body).doesNotContain("tokenId", "token_id", "tokenName", TOKEN_ID.toString());
        }
        assertThat(ru.taska.api.issue.v1.IssueHistoryResponse.getDescriptor().findFieldByName("token_id")).isNull();
        assertThat(ru.taska.api.issue.v1.IssueCommentResponse.getDescriptor().findFieldByName("token_id")).isNull();
    }

    private GatewayContextFactory factory(AccessTokenKind kind) {
        var auth = mock(GrpcAuthServiceClient.class);
        var authMapper = mock(AuthMapper.class);
        when(authMapper.toGatewayUserStatus(UserStatus.USER_STATUS_ACTIVE)).thenReturn(GatewayUserStatus.ACTIVE);
        when(authMapper.toGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)).thenReturn(GlobalRole.USER);
        var user = UserContext.newBuilder().setUserId(UUID.randomUUID().toString()).setStatus(UserStatus.USER_STATUS_ACTIVE)
                .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER).setTokenKind(kind).addAllScopes(List.of("read", "write"));
        if (kind == AccessTokenKind.ACCESS_TOKEN_KIND_PAT) user.setTokenId(TOKEN_ID.toString());
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(
                ValidateAccessTokenResponse.newBuilder().setUserContext(user).build()));
        var result = new GatewayContextFactory(new BearerTokenExtractor(), auth, new ContextMapper(authMapper), verifier());
        ReflectionTestUtils.setField(result, "nodeId", "api-gateway");
        return result;
    }
}
