package ru.taska.security.pat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AuthenticateTenantPersonalTokenRequest;
import ru.taska.api.b2b.v1.CreateTenantPersonalTokenRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.PersonalToken;
import ru.taska.api.b2b.v1.PersonalTokenCreated;
import ru.taska.api.b2b.v1.PersonalTokenRequest;
import ru.taska.api.b2b.v1.ReactorTenantPersonalTokenServiceGrpc;
import ru.taska.api.b2b.v1.TenantPersonalTokenBinding;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** TAS-463 RPC. Issuance requires the verified tenant web session of the requested tenant. */
@GrpcService
@ConditionalOnProperty(name = "taska.b2b.tenant-tokens.enabled", havingValue = "true")
public final class TenantPersonalTokenGrpcService
    extends ReactorTenantPersonalTokenServiceGrpc.TenantPersonalTokenServiceImplBase {
  private final TenantPersonalTokenStore tokens;
  private final TenantPersonalTokenAdmission admission;

  public TenantPersonalTokenGrpcService(
      TenantPersonalTokenStore tokens, TenantPersonalTokenAdmission admission) {
    this.tokens = tokens;
    this.admission = admission;
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

  private static List<String> scopes(List<PersonalTokenRequest.ScopesValue> values) {
    var result = new ArrayList<String>(values.size());
    for (var value : values)
      result.add(
          switch (value) {
            case SCOPES_read -> "read";
            case SCOPES_write -> "write";
            default -> throw invalid("Unknown token scope");
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
                  || verified.tenantId() == null)
                return Mono.error(denied("SESSION_REQUIRED"));
              if (!tenant.toString().equals(context.getTenantId())
                  || !tenant.toString().equals(verified.tenantId()))
                return Mono.error(denied("TENANT_MISMATCH"));
              return Mono.just(verified);
            });
  }

  @Override
  public Mono<PersonalTokenCreated> createTenantPersonalToken(
      Mono<CreateTenantPersonalTokenRpcRequest> request) {
    return errors(
        RpcContextBridge.inbound(
            request.flatMap(
                req -> {
                  if (!req.hasPayload() || !req.hasMutation())
                    return Mono.error(invalid("Missing token fields"));
                  UUID key = id(req.getMutation().getIdempotencyKey());
                  var payload = req.getPayload();
                  UUID tenant = id(payload.getTenantId());
                  List<String> scopes = scopes(payload.getScopesList());
                  return tenantSession(tenant)
                      .flatMap(
                          verified -> {
                            var context = RpcContextBridge.details(verified).getContext();
                            UUID actor = id(context.getActorUserId());
                            return admission
                                .admit(tenant, actor)
                                .then(
                                    Mono.defer(
                                        () ->
                                            tokens.issue(
                                                actor,
                                                id(context.getSessionId()),
                                                tenant,
                                                payload.getName(),
                                                scopes,
                                                payload.getExpiresInDays(),
                                                key,
                                                verified.requestId())));
                          })
                      .map(TenantPersonalTokenGrpcService::created);
                })));
  }

  @Override
  public Mono<TenantPersonalTokenBinding> authenticateTenantPersonalToken(
      Mono<AuthenticateTenantPersonalTokenRequest> request) {
    return errors(
        request
            .flatMap(req -> tokens.authenticate(req.getSecret()))
            .map(
                binding ->
                    TenantPersonalTokenBinding.newBuilder()
                        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
                        .setActorUserId(binding.userId().toString())
                        .setTenantId(binding.tenantId().toString())
                        .setCredentialId(binding.tokenId().toString())
                        .setGrantId(binding.grantId().toString())
                        .setGrantGeneration(Long.toString(binding.grantVersion()))
                        .setActorGeneration(Long.toString(binding.authVersion()))
                        .addAllScopes(binding.scopes())
                        .setExpiresAt(binding.expiresAt().toString())
                        .build()));
  }

  private static PersonalTokenCreated created(TenantPersonalTokenStore.Issued issued) {
    var metadata =
        PersonalToken.newBuilder()
            .setId(issued.tokenId().toString())
            .setTenantId(issued.tenantId().toString())
            .setName(issued.name())
            .setCreatedAt(issued.createdAt().toString())
            .setExpiresAt(issued.expiresAt().toString())
            .setRevoked(false);
    for (String scope : issued.scopes())
      metadata.addScopes(
          "write".equals(scope)
              ? PersonalToken.ScopesValue.SCOPES_write
              : PersonalToken.ScopesValue.SCOPES_read);
    return PersonalTokenCreated.newBuilder()
        .setMetadata(metadata)
        .setSecret(issued.secret())
        .build();
  }
}
