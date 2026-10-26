package ru.taska.transport.grpc;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.CreatePersonalAccessTokenRequest;
import ru.taska.api.auth.v1.ListPersonalAccessTokensRequest;
import ru.taska.api.auth.v1.PersonalAccessTokenMetadata;
import ru.taska.api.auth.v1.ReactorPersonalAccessTokenServiceGrpc;
import ru.taska.api.auth.v1.RevokePersonalAccessTokenRequest;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.CreatePersonalAccessTokenRequestDto;
import ru.taska.domain.dto.CreatePersonalAccessTokenResponseDto;
import ru.taska.domain.dto.PersonalAccessTokenDto;
import ru.taska.domain.dto.PersonalAccessTokenScopeDto;
import ru.taska.domain.dto.PersonalAccessTokensResponseDto;
import ru.taska.domain.dto.PersonalTokenPolicyDto;
import ru.taska.mapper.MappingUtils;

@Component
@RequiredArgsConstructor
public class GrpcPersonalAccessTokenClient {
  private final ReactorPersonalAccessTokenServiceGrpc.ReactorPersonalAccessTokenServiceStub stub;
  private final GrpcClientProperties properties;

  public Mono<CreatePersonalAccessTokenResponseDto> create(
      CreatePersonalAccessTokenRequestDto body, String session, GatewayContext context) {
    var request =
        CreatePersonalAccessTokenRequest.newBuilder()
            .setHeader(MappingUtils.buildGrpcHeader(context))
            .setSessionAccessToken(session)
            .setName(body.getName().trim())
            .addAllScopes(
                body.getScopes().stream().map(PersonalAccessTokenScopeDto::getValue).toList());
    if (body.getExpiresInDays() != null) request.setExpiresInDays(body.getExpiresInDays());
    return dynamicStub()
        .createPersonalAccessToken(request.build())
        .map(
            response ->
                new CreatePersonalAccessTokenResponseDto()
                    .token(metadata(response.getToken()))
                    .secret(response.getSecret()));
  }

  public Mono<PersonalAccessTokensResponseDto> list(String session, GatewayContext context) {
    return dynamicStub()
        .listPersonalAccessTokens(
            ListPersonalAccessTokensRequest.newBuilder()
                .setHeader(MappingUtils.buildGrpcHeader(context))
                .setSessionAccessToken(session)
                .build())
        .map(
            response ->
                new PersonalAccessTokensResponseDto()
                    .items(
                        response.getItemsList().stream()
                            .map(GrpcPersonalAccessTokenClient::metadata)
                            .toList())
                    .policy(
                        new PersonalTokenPolicyDto()
                            .maxLifetimeDays(response.getMaxLifetimeDays())
                            .maxActiveTokens(response.getMaxActiveTokens())));
  }

  public Mono<Void> revoke(String id, String session, GatewayContext context) {
    return dynamicStub()
        .revokePersonalAccessToken(
            RevokePersonalAccessTokenRequest.newBuilder()
                .setHeader(MappingUtils.buildGrpcHeader(context))
                .setSessionAccessToken(session)
                .setTokenId(id)
                .build())
        .then();
  }

  private ReactorPersonalAccessTokenServiceGrpc.ReactorPersonalAccessTokenServiceStub
      dynamicStub() {
    return stub.withDeadlineAfter(
        properties.authService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
  }

  private static PersonalAccessTokenDto metadata(PersonalAccessTokenMetadata token) {
    return new PersonalAccessTokenDto()
        .id(java.util.UUID.fromString(token.getId()))
        .name(token.getName())
        .tokenPrefix(token.getTokenPrefix())
        .scopes(token.getScopesList().stream().map(PersonalAccessTokenScopeDto::fromValue).toList())
        .createdAt(MappingUtils.toOffsetDateTime(token.getCreatedAt()))
        .expiresAt(MappingUtils.toOffsetDateTime(token.getExpiresAt()))
        .lastUsedAt(
            token.hasLastUsedAt() ? MappingUtils.toOffsetDateTime(token.getLastUsedAt()) : null);
  }
}
