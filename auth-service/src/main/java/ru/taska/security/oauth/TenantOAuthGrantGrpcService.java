package ru.taska.security.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ApproveTenantOAuthGrantRpcRequest;
import ru.taska.api.b2b.v1.AuthenticateTenantOAuthTokenRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.OAuthConsentRequest;
import ru.taska.api.b2b.v1.OAuthConsentResult;
import ru.taska.api.b2b.v1.ReactorTenantOAuthGrantServiceGrpc;
import ru.taska.api.b2b.v1.TenantOAuthTokenBinding;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.session.WebSessionStore;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** TAS-498 RPC. Approval requires the verified tenant web session of the selected tenant. */
@GrpcService
@ConditionalOnProperty(name = "taska.b2b.tenant-oauth.enabled", havingValue = "true")
public final class TenantOAuthGrantGrpcService
    extends ReactorTenantOAuthGrantServiceGrpc.TenantOAuthGrantServiceImplBase {
  private final TenantOAuthGrantStore grants;
  private final WebSessionStore.MembershipAuthority memberships;
  private final OAuthIntrospectionClient introspection;

  public TenantOAuthGrantGrpcService(
      TenantOAuthGrantStore grants,
      WebSessionStore.MembershipAuthority memberships,
      OAuthIntrospectionClient introspection) {
    this.grants = grants;
    this.memberships = memberships;
    this.introspection = introspection;
  }

  private static UUID id(String raw) {
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException error) {
      throw invalid("Invalid UUID");
    }
  }

  private static DomainException invalid(String message) {
    return new DomainException(
        DomainStatus.INVALID_ARGUMENT, message, Map.of("code", "VALIDATION_FAILED"));
  }

  private static DomainException denied(String code) {
    return new DomainException(DomainStatus.PERMISSION_DENIED, code, Map.of("code", code));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static List<String> scopes(List<OAuthConsentRequest.ScopesValue> values) {
    var result = new ArrayList<String>(values.size());
    for (var value : values)
      result.add(
          switch (value) {
            case SCOPES_read -> "read";
            case SCOPES_write -> "write";
            default -> throw invalid("Unknown consent scope");
          });
    return result;
  }

  /** The tenant comes from the verified session; the payload may only repeat it. */
  private static Mono<VerifiedTenantContext> tenantSession(UUID tenant) {
    return VerifiedTenantContext.current()
        .flatMap(
            verified -> {
              var context = RpcContextBridge.details(verified).getContext();
              if (context.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                  || !context.hasActorUserId()
                  || !context.hasSessionId()
                  || !context.hasTenantId()
                  || !context.hasMembershipGeneration()
                  || verified.tenantId() == null)
                return Mono.error(denied("SESSION_REQUIRED"));
              if (!tenant.toString().equals(context.getTenantId())
                  || !tenant.toString().equals(verified.tenantId()))
                return Mono.error(denied("TENANT_MISMATCH"));
              // Issuing a grant is a mutation of the account's integrations.
              if (!verified.scopes().contains("write")) return Mono.error(denied("SCOPE_DENIED"));
              return Mono.just(verified);
            });
  }

  /** Current ACTIVE membership at the generation the tenant session was opened with. */
  private Mono<Long> membership(UUID tenant, UUID actor, String generation) {
    return memberships
        .current(tenant, actor)
        .onErrorMap(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.PERMISSION_DENIED,
            error -> denied("MEMBERSHIP_INACTIVE"))
        .onErrorMap(
            error -> !(error instanceof DomainException),
            error ->
                new DomainException(
                    DomainStatus.UNAVAILABLE,
                    "Membership authority unavailable",
                    Map.of("code", "DEPENDENCY_UNAVAILABLE")))
        .switchIfEmpty(Mono.error(denied("MEMBERSHIP_INACTIVE")))
        .flatMap(
            version ->
                Long.toString(version).equals(generation)
                    ? Mono.just(version)
                    : Mono.error(denied("MEMBERSHIP_INACTIVE")));
  }

  @Override
  public Mono<OAuthConsentResult> approveTenantOAuthGrant(
      Mono<ApproveTenantOAuthGrantRpcRequest> request) {
    return errors(
        RpcContextBridge.inbound(
            request.flatMap(
                req -> {
                  if (!req.hasPayload() || !req.hasMutation())
                    return Mono.error(invalid("Missing consent fields"));
                  var payload = req.getPayload();
                  // A refusal creates no grant; the browser posts it to the authorization server.
                  if (!payload.getApproved()) return Mono.error(invalid("Consent not approved"));
                  UUID key = id(req.getMutation().getIdempotencyKey());
                  UUID tenant = id(payload.getTenantId());
                  List<String> scopes = scopes(payload.getScopesList());
                  return tenantSession(tenant)
                      .flatMap(
                          verified -> {
                            var context = RpcContextBridge.details(verified).getContext();
                            UUID actor = id(context.getActorUserId());
                            return membership(tenant, actor, context.getMembershipGeneration())
                                .flatMap(
                                    version ->
                                        grants.approve(
                                            actor,
                                            id(context.getSessionId()),
                                            version,
                                            tenant,
                                            payload.getClientId(),
                                            scopes,
                                            payload.getAuthorizationRequestId(),
                                            key));
                          })
                      .map(
                          approved ->
                              OAuthConsentResult.newBuilder()
                                  .setGrantId(approved.grantId().toString())
                                  .setTenantId(approved.tenantId().toString())
                                  .setRedirectPath(
                                      resume(
                                          payload.getAuthorizationRequestId(),
                                          approved.grantId()))
                                  .build());
                })));
  }

  /** Authorization-server consent page that completes this request with the approved grant. */
  static String resume(String authorizationRequest, UUID grant) {
    return "/consent?state="
        + URLEncoder.encode(authorizationRequest, StandardCharsets.UTF_8)
        + "&tenant_grant="
        + grant;
  }

  @Override
  public Mono<TenantOAuthTokenBinding> authenticateTenantOAuthToken(
      Mono<AuthenticateTenantOAuthTokenRequest> request) {
    return errors(
        request
            .flatMap(req -> introspection.introspect(req.getAccessToken()))
            .flatMap(
                identity -> {
                  // Legacy unbound authorizations never authenticate as a tenant credential.
                  if (identity.integrationGrant() == null)
                    return Mono.error(OAuthIntrospectionClient.invalid());
                  return grants
                      .validate(
                          identity.integrationGrant(),
                          identity.subject(),
                          identity.clientId(),
                          identity.grantVersion(),
                          identity.tenant())
                      .onErrorMap(
                          error ->
                              error instanceof DomainException domain
                                  && domain.getStatus() == DomainStatus.NOT_FOUND,
                          error -> OAuthIntrospectionClient.invalid())
                      .map(
                          binding ->
                              TenantOAuthTokenBinding.newBuilder()
                                  .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_OAUTH)
                                  .setActorUserId(binding.userId().toString())
                                  .setTenantId(binding.tenantId().toString())
                                  .setCredentialId(identity.grantId().toString())
                                  .setGrantId(binding.grantId().toString())
                                  .setGrantGeneration(Long.toString(binding.grantVersion()))
                                  .setActorGeneration(Long.toString(binding.authVersion()))
                                  // The token never carries more than the grant approved.
                                  .addAllScopes(
                                      identity.scopes().stream()
                                          .filter(binding.scopes()::contains)
                                          .toList())
                                  .setExpiresAt(
                                      (identity.expiresAt().isBefore(binding.expiresAt())
                                              ? identity.expiresAt()
                                              : binding.expiresAt())
                                          .toString())
                                  .build());
                }));
  }
}
