package ru.taska.transport.grpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.oauth.v1.*;
import ru.taska.config.GrpcClientConfig;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;
import ru.taska.mapper.MappingUtils;

@Component
public class GrpcConnectedAppsClient {
  private final ReactorConnectedAppsServiceGrpc.ReactorConnectedAppsServiceStub stub;
  private final GrpcClientProperties properties;

  public GrpcConnectedAppsClient(GrpcClientConfig config, GrpcClientProperties properties) {
    this.stub = ReactorConnectedAppsServiceGrpc.newReactorStub(config.authManagedChannel());
    this.properties = properties;
  }

  public Mono<ConnectedAppsResponseDto> list(String session, GatewayContext context) {
    return dynamicStub()
        .listConnectedApps(
            ListConnectedAppsRequest.newBuilder()
                .setHeader(MappingUtils.buildGrpcHeader(context))
                .setUserId(context.userContext().userId().toString())
                .setSessionAccessToken(session)
                .build())
        .map(
            response ->
                new ConnectedAppsResponseDto()
                    .available(response.getAvailable())
                    .items(
                        response.getItemsList().stream()
                            .map(GrpcConnectedAppsClient::app)
                            .toList()));
  }

  public Mono<Void> revoke(UUID appId, String session, GatewayContext context) {
    return dynamicStub()
        .revokeConnectedApp(
            RevokeConnectedAppRequest.newBuilder()
                .setHeader(MappingUtils.buildGrpcHeader(context))
                .setUserId(context.userContext().userId().toString())
                .setSessionAccessToken(session)
                .setAppId(appId.toString())
                .build())
        .then();
  }

  private ReactorConnectedAppsServiceGrpc.ReactorConnectedAppsServiceStub dynamicStub() {
    return stub.withDeadlineAfter(
        properties.authService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
  }

  private static ConnectedAppDto app(ConnectedApp value) {
    return new ConnectedAppDto()
        .appId(UUID.fromString(value.getAppId()))
        .name(value.getName())
        .host(value.getHost())
        .metadataUrl(value.hasMetadataUrl() ? java.net.URI.create(value.getMetadataUrl()) : null)
        .scopes(value.getScopesList())
        .connectedAt(
            value.hasConnectedAt() ? MappingUtils.toOffsetDateTime(value.getConnectedAt()) : null)
        .lastUsedAt(
            value.hasLastUsedAt() ? MappingUtils.toOffsetDateTime(value.getLastUsedAt()) : null)
        .expiresAt(
            value.hasExpiresAt() ? MappingUtils.toOffsetDateTime(value.getExpiresAt()) : null);
  }
}
