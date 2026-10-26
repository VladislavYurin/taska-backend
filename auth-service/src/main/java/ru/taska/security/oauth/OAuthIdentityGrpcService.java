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

  public OAuthIdentityGrpcService(AuthService auth, OAuthTokenService oauth) {
    this.auth = auth;
    this.oauth = oauth;
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
