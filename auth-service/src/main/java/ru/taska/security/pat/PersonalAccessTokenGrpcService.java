package ru.taska.security.pat;

import com.google.protobuf.Empty;
import exception.GrpcExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.Header;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.AuthService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class PersonalAccessTokenGrpcService extends ReactorPersonalAccessTokenServiceGrpc.PersonalAccessTokenServiceImplBase {
    private final AuthService auth;
    private final PersonalAccessTokenService tokens;

    @Override
    public Mono<CreatePersonalAccessTokenResponse> createPersonalAccessToken(Mono<CreatePersonalAccessTokenRequest> request) {
        return request.flatMap(req -> owner(req.getHeader(), req.getSessionAccessToken())
                        .flatMap(owner -> tokens.create(owner, req.getName(), req.getScopesList(),
                                req.hasExpiresInDays() ? req.getExpiresInDays() : null, req.getHeader().getRequestId()))
                        .map(issued -> CreatePersonalAccessTokenResponse.newBuilder()
                                .setToken(metadata(issued.token())).setSecret(issued.secret()).build()))
                .transform(GrpcExceptionHandler.withErrorHandling("CreatePersonalAccessToken"));
    }

    @Override
    public Mono<ListPersonalAccessTokensResponse> listPersonalAccessTokens(Mono<ListPersonalAccessTokensRequest> request) {
        return request.flatMap(req -> owner(req.getHeader(), req.getSessionAccessToken())
                        .flatMapMany(tokens::list).map(PersonalAccessTokenGrpcService::metadata).collectList()
                        .map(items -> ListPersonalAccessTokensResponse.newBuilder().addAllItems(items)
                                .setMaxLifetimeDays(tokens.policy().getMaxLifetimeDays())
                                .setMaxActiveTokens(tokens.policy().getMaxActiveTokens()).build()))
                .transform(GrpcExceptionHandler.withErrorHandling("ListPersonalAccessTokens"));
    }

    @Override
    public Mono<Empty> revokePersonalAccessToken(Mono<RevokePersonalAccessTokenRequest> request) {
        return request.flatMap(req -> owner(req.getHeader(), req.getSessionAccessToken()).flatMap(owner ->
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getTokenId(), "tokenId")
                                .flatMap(id -> tokens.revoke(owner, id, req.getHeader().getRequestId()))))
                .thenReturn(Empty.getDefaultInstance())
                .transform(GrpcExceptionHandler.withErrorHandling("RevokePersonalAccessToken"));
    }

    private Mono<UUID> owner(Header header, String session) {
        return Mono.zip(GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getNodeId(), "header.nodeId"))
                .then(Mono.defer(() -> auth.validateAccessToken(session)))
                .flatMap(context -> context.getTokenKind() == AccessTokenKind.ACCESS_TOKEN_KIND_JWT
                        ? Mono.just(UUID.fromString(context.getUserId()))
                        : Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                                "A JWT session is required", Map.of("reason", "SESSION_REQUIRED"))));
    }

    private static PersonalAccessTokenMetadata metadata(PersonalAccessToken token) {
        var builder = PersonalAccessTokenMetadata.newBuilder().setId(token.getId().toString()).setName(token.getName())
                .setTokenPrefix(token.getTokenPrefix()).addAllScopes(List.of(token.getScopes()))
                .setCreatedAt(PersonalAccessTokenService.timestamp(token.getCreatedAt()))
                .setExpiresAt(PersonalAccessTokenService.timestamp(token.getExpiresAt()));
        if (token.getLastUsedAt() != null) builder.setLastUsedAt(PersonalAccessTokenService.timestamp(token.getLastUsedAt()));
        return builder.build();
    }
}
