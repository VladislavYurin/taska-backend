package ru.taska.security.oauth;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import exception.GrpcExceptionHandler;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.Header;
import ru.taska.api.oauth.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.AuthService;
import tools.jackson.databind.JsonNode;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class ConnectedAppsGrpcService
    extends ReactorConnectedAppsServiceGrpc.ConnectedAppsServiceImplBase {
  private final AuthService auth;
  private final OAuthIntrospectionClient client;

  @Override
  public Mono<ListConnectedAppsResponse> listConnectedApps(Mono<ListConnectedAppsRequest> request) {
    return request
        .flatMap(
            req ->
                owner(req.getHeader(), req.getSessionAccessToken(), req.getUserId())
                    .flatMap(
                        id -> {
                          if (!client.enabled())
                            return Mono.just(
                                ListConnectedAppsResponse.newBuilder().setAvailable(false).build());
                          return client
                              .listConnectedApps(id)
                              .map(
                                  items -> {
                                    if (!items.isArray())
                                      throw new DomainException(
                                          DomainStatus.UNAVAILABLE, "Invalid OAuth app response");
                                    var result =
                                        ListConnectedAppsResponse.newBuilder().setAvailable(true);
                                    items.forEach(item -> result.addItems(app(item)));
                                    return result.build();
                                  });
                        }))
        .transform(GrpcExceptionHandler.withErrorHandling("ListConnectedApps"));
  }

  @Override
  public Mono<Empty> revokeConnectedApp(Mono<RevokeConnectedAppRequest> request) {
    return request
        .flatMap(
            req ->
                owner(req.getHeader(), req.getSessionAccessToken(), req.getUserId())
                    .flatMap(
                        id ->
                            GrpcRequestValidators.parseUuidOrInvalidArgument(
                                    req.getAppId(), "appId")
                                .flatMap(
                                    appId ->
                                        client.enabled()
                                            ? client.revokeConnectedApp(id, appId.toString())
                                            : Mono.empty())))
        .thenReturn(Empty.getDefaultInstance())
        .transform(GrpcExceptionHandler.withErrorHandling("RevokeConnectedApp"));
  }

  private Mono<UUID> owner(Header header, String session, String requestedOwner) {
    return Mono.when(
            GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                header.getRequestId(), "header.requestId"),
            GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                header.getNodeId(), "header.nodeId"))
        .then(Mono.defer(() -> auth.validateAccessToken(session)))
        .flatMap(
            context -> {
              if (context.getTokenKind() != AccessTokenKind.ACCESS_TOKEN_KIND_JWT)
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "A JWT session is required",
                        Map.of("reason", "SESSION_REQUIRED")));
              if (!context.getUserId().equals(requestedOwner))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "App owner does not match session"));
              return Mono.just(UUID.fromString(context.getUserId()));
            });
  }

  private static ConnectedApp app(JsonNode node) {
    var app =
        ConnectedApp.newBuilder()
            .setAppId(node.path("appId").asText())
            .setName(node.path("name").asText())
            .setHost(node.path("host").asText());
    if (!node.path("metadataUrl").isNull() && node.has("metadataUrl"))
      app.setMetadataUrl(node.path("metadataUrl").asText());
    node.path("scopes").forEach(scope -> app.addScopes(scope.asText()));
    if (node.hasNonNull("connectedAt"))
      app.setConnectedAt(timestamp(node.path("connectedAt").asText()));
    if (node.hasNonNull("lastUsedAt"))
      app.setLastUsedAt(timestamp(node.path("lastUsedAt").asText()));
    if (node.hasNonNull("expiresAt")) app.setExpiresAt(timestamp(node.path("expiresAt").asText()));
    return app.build();
  }

  private static Timestamp timestamp(String value) {
    Instant instant = Instant.parse(value);
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }
}
