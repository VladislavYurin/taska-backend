package ru.taska.security.session;

import com.google.protobuf.Empty;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AccessSession;
import ru.taska.api.b2b.v1.CreateTenantSessionRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.LoginResult;
import ru.taska.api.b2b.v1.ReactorAuthSessionServiceGrpc;
import ru.taska.api.b2b.v1.RefreshWebSessionRpcRequest;
import ru.taska.api.b2b.v1.RevokeTenantSessionRpcRequest;
import ru.taska.api.b2b.v1.RevokeWebSessionRpcRequest;
import ru.taska.api.b2b.v1.StartWebSessionRpcRequest;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.service.AuthService;

/** B2B session RPC. Tenant commands require the verified gateway context. */
@GrpcService
@ConditionalOnProperty(name = "taska.b2b.sessions.enabled", havingValue = "true")
public final class AuthSessionGrpcService
    extends ReactorAuthSessionServiceGrpc.AuthSessionServiceImplBase {
  private final AuthService auth;
  private final WebSessionStore sessions;
  private final TenantAccessIssuer issuer;

  public AuthSessionGrpcService(
      AuthService auth, WebSessionStore sessions, TenantAccessIssuer issuer) {
    this.auth = auth;
    this.sessions = sessions;
    this.issuer = issuer;
  }

  private static UUID id(String raw) {
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException error) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid UUID");
    }
  }

  private AccessSession response(WebSessionStore.Issued issued) {
    var result =
        AccessSession.newBuilder()
            .setSessionId(issued.sessionId().toString())
            .setUserId(issued.userId().toString())
            .setAccessToken(issuer.issue(issued))
            .setRefreshToken(issued.refreshToken())
            .setTokenType(AccessSession.TokenTypeValue.TOKEN_TYPE_Bearer)
            .setExpiresIn(issuer.expiresIn(issued))
            .setMfaVerified(false);
    if (issued.tenantId() != null) result.setTenantId(issued.tenantId().toString());
    return result.build();
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<LoginResult> startWebSession(Mono<StartWebSessionRpcRequest> request) {
    return errors(
        request.flatMap(
            req -> {
              if (!req.hasPayload() || !req.hasMutation())
                return Mono.error(
                    new DomainException(DomainStatus.INVALID_ARGUMENT, "Missing login fields"));
              UUID key = id(req.getMutation().getIdempotencyKey());
              return auth.authenticateOAuthCredentials(
                      req.getPayload().getEmail(), req.getPayload().getPassword())
                  .flatMap(user -> sessions.openGlobal(id(user.getUserId()), "PASSWORD", key))
                  .map(
                      issued ->
                          LoginResult.newBuilder().setAccessSession(response(issued)).build());
            }));
  }

  @Override
  public Mono<AccessSession> refreshWebSession(Mono<RefreshWebSessionRpcRequest> request) {
    return errors(
        request.flatMap(
            req -> {
              if (!req.hasPayload() || !req.hasMutation())
                return Mono.error(
                    new DomainException(DomainStatus.INVALID_ARGUMENT, "Missing refresh fields"));
              return sessions
                  .rotate(
                      req.getPayload().getRefreshToken(), id(req.getMutation().getIdempotencyKey()))
                  .map(this::response);
            }));
  }

  private Mono<VerifiedTenantContext> globalContext() {
    return VerifiedTenantContext.current()
        .flatMap(
            verified -> {
              var context = RpcContextBridge.details(verified).getContext();
              if (context.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !context.hasActorUserId()
                  || !context.hasSessionId()
                  || context.hasTenantId()
                  || !verified.scopes().contains("tenants:select"))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "Global session required",
                        java.util.Map.of("code", "SCOPE_DENIED")));
              return Mono.just(verified);
            });
  }

  @Override
  public Mono<AccessSession> createTenantSession(Mono<CreateTenantSessionRpcRequest> request) {
    return errors(
        RpcContextBridge.inbound(
            request.flatMap(
                req -> {
                  if (!req.hasPayload() || !req.hasMutation())
                    return Mono.error(
                        new DomainException(
                            DomainStatus.INVALID_ARGUMENT, "Missing tenant fields"));
                  UUID key = id(req.getMutation().getIdempotencyKey());
                  UUID tenant = id(req.getPayload().getTenantId());
                  return globalContext()
                      .flatMap(
                          verified -> {
                            var context = RpcContextBridge.details(verified).getContext();
                            return sessions
                                .openTenant(
                                    id(context.getActorUserId()),
                                    id(context.getSessionId()),
                                    tenant,
                                    key)
                                .map(this::response);
                          });
                })));
  }

  @Override
  public Mono<Empty> revokeTenantSession(Mono<RevokeTenantSessionRpcRequest> request) {
    return errors(
        RpcContextBridge.inbound(
            request.flatMap(
                req -> {
                  if (!req.hasMutation())
                    return Mono.error(
                        new DomainException(DomainStatus.INVALID_ARGUMENT, "Missing mutation"));
                  id(req.getMutation().getIdempotencyKey());
                  UUID session = id(req.getSessionId());
                  return globalContext()
                      .flatMap(
                          verified -> {
                            var context = RpcContextBridge.details(verified).getContext();
                            return sessions
                                .revokeTenant(id(context.getActorUserId()), session)
                                .thenReturn(Empty.getDefaultInstance());
                          });
                })));
  }

  @Override
  public Mono<Empty> revokeWebSession(Mono<RevokeWebSessionRpcRequest> request) {
    return errors(
        RpcContextBridge.inbound(
            request.flatMap(
                req -> {
                  if (!req.hasMutation())
                    return Mono.error(
                        new DomainException(DomainStatus.INVALID_ARGUMENT, "Missing mutation"));
                  id(req.getMutation().getIdempotencyKey());
                  return globalContext()
                      .flatMap(
                          verified -> {
                            var context = RpcContextBridge.details(verified).getContext();
                            return sessions
                                .revokeWeb(id(context.getActorUserId()), id(context.getSessionId()))
                                .thenReturn(Empty.getDefaultInstance());
                          });
                })));
  }
}
