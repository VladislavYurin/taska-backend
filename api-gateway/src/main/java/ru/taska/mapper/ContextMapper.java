package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayTokenKind;
import ru.taska.domain.GatewayUserContext;

@Component
@RequiredArgsConstructor
public class ContextMapper {

  private final AuthMapper authMapper;

  public GatewayUserContext mapToGatewayUserContext(UserContext proto) {
    return new GatewayUserContext(
        proto.getUserId(),
        proto.getEmail(),
        proto.getDisplayName(),
        authMapper.toGatewayUserStatus(proto.getStatus()),
        authMapper.toGlobalRole(proto.getGlobalRole()),
        switch (proto.getTokenKind()) {
          case ACCESS_TOKEN_KIND_DELEGATION -> GatewayTokenKind.DELEGATION;
          case ACCESS_TOKEN_KIND_PAT -> GatewayTokenKind.PAT;
          // An old auth-service emits UNSPECIFIED only for its legacy JWT path.
          case ACCESS_TOKEN_KIND_JWT, ACCESS_TOKEN_KIND_UNSPECIFIED -> GatewayTokenKind.JWT;
          default ->
              throw io.grpc.Status.UNAUTHENTICATED
                  .withDescription("Unsupported access token kind")
                  .asRuntimeException();
        },
        proto.hasTokenId() ? proto.getTokenId() : null,
        proto.getScopesList());
  }
}
