package ru.taska.security.oauth;

import exception.GrpcExceptionHandler;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.api.auth.oauth.v1.*;
import ru.taska.api.auth.v1.*;
import ru.taska.service.AuthService;

@GrpcService
public class OAuthIdentityGrpcService
    extends ReactorOAuthIdentityServiceGrpc.OAuthIdentityServiceImplBase {
  private final AuthService auth;
  private final OAuthTokenService oauth;
  private final ru.taska.oauth.OAuthFlowService flow;
  private final ru.taska.oauth.OAuthProviderCatalog providers;
  private final ObjectProvider<TenantOAuthGrantStore> tenantGrants;

  public OAuthIdentityGrpcService(AuthService auth, OAuthTokenService oauth, ru.taska.oauth.OAuthFlowService flow, ru.taska.oauth.OAuthProviderCatalog providers,
      ObjectProvider<TenantOAuthGrantStore> tenantGrants) {
    this.auth = auth;
    this.oauth = oauth;
    this.flow = flow;
    this.providers = providers;
    this.tenantGrants = tenantGrants;
  }

  @Override
  public Mono<ValidateAccessTokenResponse> authenticateCredentials(Mono<LoginRequest> request) {
    return request
        .flatMap(
            req ->
                auth.authenticateOAuthCredentials(
                    req.getBody().getEmail(), req.getBody().getPassword()))
        .map(user -> ValidateAccessTokenResponse.newBuilder().setUserContext(user).build())
        .transform(GrpcExceptionHandler.withErrorHandling("oauthCredentials"));
  }

  @Override
  public Mono<ValidateAccessTokenResponse> claimSignIn(Mono<ClaimOAuthSignInRequest> request) {
    return request.flatMap(req -> reactor.core.publisher.Mono.when(
            validator.GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
            validator.GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"))
            .then(reactor.core.publisher.Mono.defer(() -> flow.claimSignIn(req.getCode(), req.getNonce(), req.getHeader().getRequestId()))))
        .map(user -> ValidateAccessTokenResponse.newBuilder().setUserContext(user).build())
        .transform(GrpcExceptionHandler.withErrorHandling("oauthClaimSignIn"));
  }

  @Override
  public Mono<ListOAuthSignInProvidersResponse> listSignInProviders(Mono<com.google.protobuf.Empty> request) {
    return request.map(ignored -> ListOAuthSignInProvidersResponse.newBuilder().addAllProviders(providers.enabledProviders()).build())
        .transform(GrpcExceptionHandler.withErrorHandling("oauthSignInProviders"));
  }

  @Override
  public Mono<ValidateAccessTokenResponse> validateSubject(
      Mono<ValidateOAuthSubjectRequest> request) {
    return request
        .flatMap(req -> oauth.validateSubject(req.getSubject()))
        .map(user -> ValidateAccessTokenResponse.newBuilder().setUserContext(user).build())
        .transform(GrpcExceptionHandler.withErrorHandling("oauthSubject"));
  }

  @Override
  public Mono<ExchangeOAuthTokenResponse> exchangeToken(Mono<ExchangeOAuthTokenRequest> request) {
    return request
        .flatMap(req -> oauth.exchange(req.getAccessToken()))
        .transform(GrpcExceptionHandler.withErrorHandling("oauthExchange"));
  }

  @Override
  public Mono<TenantOAuthGrantBinding> claimTenantOAuthGrant(
      Mono<ClaimTenantOAuthGrantRequest> request) {
    return request
        .flatMap(
            req ->
                store()
                    .flatMap(
                        grants ->
                            grants.claim(
                                id(req.getGrantId()),
                                id(req.getUserId()),
                                req.getClientId(),
                                req.getAuthorizationRequestId())))
        .map(OAuthIdentityGrpcService::binding)
        .transform(GrpcExceptionHandler.withErrorHandling("oauthTenantGrantClaim"));
  }

  @Override
  public Mono<TenantOAuthGrantBinding> validateTenantOAuthGrant(
      Mono<ValidateTenantOAuthGrantRequest> request) {
    return request
        .flatMap(
            req ->
                store()
                    .flatMap(
                        grants ->
                            grants.validate(
                                id(req.getGrantId()),
                                id(req.getUserId()),
                                req.getClientId(),
                                generation(req.getGrantGeneration()),
                                null)))
        .map(OAuthIdentityGrpcService::binding)
        .transform(GrpcExceptionHandler.withErrorHandling("oauthTenantGrantValidate"));
  }

  /** Disabled tenant grants fail closed; the authorization server then refuses the consent. */
  private Mono<TenantOAuthGrantStore> store() {
    TenantOAuthGrantStore grants = tenantGrants.getIfAvailable();
    return grants == null
        ? Mono.error(
            new DomainException(DomainStatus.FAILED_PRECONDITION, "Tenant OAuth grants disabled"))
        : Mono.just(grants);
  }

  private static UUID id(String raw) {
    try {
      UUID parsed = UUID.fromString(raw);
      if (parsed.toString().equals(raw)) return parsed;
    } catch (RuntimeException ignored) {
      // Reported below without echoing the value.
    }
    throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid UUID");
  }

  private static long generation(String raw) {
    try {
      long value = Long.parseLong(raw);
      if (value > 0 && Long.toString(value).equals(raw)) return value;
    } catch (RuntimeException ignored) {
      // Reported below without echoing the value.
    }
    throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid grant generation");
  }

  private static TenantOAuthGrantBinding binding(TenantOAuthGrantStore.Binding binding) {
    return TenantOAuthGrantBinding.newBuilder()
        .setGrantId(binding.grantId().toString())
        .setTenantId(binding.tenantId().toString())
        .addAllScopes(binding.scopes())
        .setGrantGeneration(Long.toString(binding.grantVersion()))
        .build();
  }
}
