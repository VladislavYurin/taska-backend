package ru.taska.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.config.GrpcClientConfig;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.TenantPersonalTokenCreatedDto;
import ru.taska.domain.dto.TenantPersonalTokenDto;
import ru.taska.domain.dto.TenantPersonalTokenRequestDto;
import ru.taska.security.tenant.*;

@Component
public class TenantPersonalTokenClient {
  private final ReactorTenantPersonalTokenServiceGrpc.ReactorTenantPersonalTokenServiceStub stub;
  private final ObjectProvider<RpcContextSecurity> security;

  public TenantPersonalTokenClient(GrpcClientConfig config, ObjectProvider<RpcContextSecurity> security) {
    this.stub = ReactorTenantPersonalTokenServiceGrpc.newReactorStub(config.authManagedChannel());
    this.security = security;
  }

  public Mono<TenantPersonalTokenCreatedDto> create(TenantPersonalTokenRequestDto value, UUID key, GatewayContext context) {
    return Mono.defer(() -> {
      var parent = context.verified();
      var guard = security.getIfAvailable();
      if (parent == null || guard == null) return Mono.error(Status.UNAVAILABLE.asRuntimeException());
      var claims = RpcContextBridge.details(parent).getContext();
      if (claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_TENANT || !claims.hasSessionId())
        return Mono.error(denied("SESSION_REQUIRED"));
      if (!value.getTenantId().toString().equals(parent.tenantId())) return Mono.error(denied("TENANT_MISMATCH"));
      var payload = PersonalTokenRequest.newBuilder().setTenantId(value.getTenantId().toString())
          .setName(value.getName()).setExpiresInDays(value.getExpiresInDays().longValue());
      for (var scope : value.getScopes()) payload.addScopes(
          "read".equals(scope.getValue()) ? PersonalTokenRequest.ScopesValue.SCOPES_read : PersonalTokenRequest.ScopesValue.SCOPES_write);
      var request = CreateTenantPersonalTokenRpcRequest.newBuilder()
          .setContext(guard.delegate(parent, "auth-service", "createTenantPersonalToken", Set.of("write")))
          .setMutation(MutationOptions.newBuilder().setIdempotencyKey(key.toString()))
          .setPayload(payload).build();
      return guard.sign(parent, request, TenantPersonalTokenServiceGrpc.getCreateTenantPersonalTokenMethod().getFullMethodName(),
              new RpcContextSecurity.Policy(true, true))
          .flatMap(assertion -> {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
            return stub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).createTenantPersonalToken(request);
          }).map(result -> {
            var token = result.getMetadata();
            if (!result.hasMetadata() || !token.getTenantId().equals(parent.tenantId()) || result.getSecret().isBlank())
              throw Status.UNAVAILABLE.withDescription("Invalid token result").asRuntimeException();
            var metadata = new TenantPersonalTokenDto().id(UUID.fromString(token.getId()))
                .tenantId(UUID.fromString(token.getTenantId())).name(token.getName()).tokenPrefix(token.getTokenPrefix())
                .createdAt(OffsetDateTime.parse(token.getCreatedAt())).expiresAt(OffsetDateTime.parse(token.getExpiresAt()))
                .revoked(token.getRevoked()).lastUsedAt(token.hasLastUsedAt() ? OffsetDateTime.parse(token.getLastUsedAt()) : null);
            for (var scope : token.getScopesList()) metadata.addScopesItem(switch (scope) {
              case SCOPES_read -> TenantPersonalTokenDto.ScopesEnum.READ;
              case SCOPES_write -> TenantPersonalTokenDto.ScopesEnum.WRITE;
              default -> throw Status.UNAVAILABLE.withDescription("Invalid token scopes").asRuntimeException();
            });
            return new TenantPersonalTokenCreatedDto().metadata(metadata).secret(result.getSecret());
          });
    });
  }

  private static io.grpc.StatusRuntimeException denied(String code) {
    return Status.PERMISSION_DENIED.withDescription(code)
        .asRuntimeException(mapper.GrpcErrorDetails.encode(Map.of("code", code)));
  }
}
