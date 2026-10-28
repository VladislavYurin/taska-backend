package ru.taska.security.oauth;

import exception.GrpcExceptionHandler;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
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

  public OAuthIdentityGrpcService(AuthService auth, OAuthTokenService oauth, ru.taska.oauth.OAuthFlowService flow, ru.taska.oauth.OAuthProviderCatalog providers) {
    this.auth = auth;
    this.oauth = oauth;
    this.flow = flow;
    this.providers = providers;
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
}
