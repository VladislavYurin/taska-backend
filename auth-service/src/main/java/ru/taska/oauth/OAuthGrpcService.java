package ru.taska.oauth;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.common.v1.Header;
import ru.taska.api.oauth.v1.*;
import ru.taska.dto.AuthResponseDto;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class OAuthGrpcService extends ReactorOAuthServiceGrpc.OAuthServiceImplBase {
    private final OAuthFlowService flow;
    private final OAuthIpLimiter ipLimiter;
    @Override public Mono<StartOAuthResponse> startOAuth(Mono<StartOAuthRequest> request) {
        return request.flatMap(req -> header(req.getHeader()).then(ipLimiter.reserve(req.getClientIp())).then(Mono.defer(() -> flow.start(req.getProvider(), req.getReturnTo()))))
                .map(value -> StartOAuthResponse.newBuilder().setAuthorizationUrl(value.authorizationUrl()).setState(value.state()).setBrowserToken(value.browserToken()).build())
                .transform(GrpcExceptionHandler.withErrorHandling("startOAuth"));
    }
    @Override public Mono<CompleteOAuthResponse> completeOAuth(Mono<CompleteOAuthRequest> request) {
        return request.flatMap(req -> header(req.getHeader()).then(ipLimiter.reserve(req.getClientIp())).then(Mono.defer(() -> flow.callback(req.getProvider(), req.getState(), req.getBrowserToken(), req.getCode(), req.getError(), req.getAppleUser()))))
                .map(value -> CompleteOAuthResponse.newBuilder().setRedirectUrl(value).build()).transform(GrpcExceptionHandler.withErrorHandling("completeOAuth"));
    }
    @Override public Mono<ExchangeOAuthResponse> exchangeOAuth(Mono<ExchangeOAuthRequest> request) {
        return request.flatMap(req -> header(req.getHeader()).then(ipLimiter.reserve(req.getClientIp())).then(Mono.defer(() -> flow.exchange(req.getCode(), req.getHeader().getRequestId()))))
                .map(value -> {
                    var builder = ExchangeOAuthResponse.newBuilder().setReturnTo(value.returnTo());
                    if (value.session() != null) builder.setStatus("SIGNED_IN").setSession(session(value.session()));
                    else {
                        var signup = OAuthSignup.newBuilder().setSignupToken(value.signupToken()).setProvider(value.identity().provider().toUpperCase(java.util.Locale.ROOT)).setEmail(value.identity().email());
                        if (value.identity().suggestedDisplayName() != null) signup.setSuggestedDisplayName(value.identity().suggestedDisplayName());
                        builder.setStatus("SIGNUP_REQUIRED").setSignup(signup);
                    }
                    return builder.build();
                }).transform(GrpcExceptionHandler.withErrorHandling("exchangeOAuth"));
    }
    @Override public Mono<LoginResponse> signupOAuth(Mono<SignupOAuthRequest> request) {
        return request.flatMap(req -> header(req.getHeader()).then(ipLimiter.reserve(req.getClientIp())).then(Mono.defer(() -> flow.signup(req.getSignupToken(), req.getDisplayName(), req.getHeader().getRequestId()))))
                .map(this::session).transform(GrpcExceptionHandler.withErrorHandling("signupOAuth"));
    }
    private Mono<Void> header(Header header) {
        return Mono.when(GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getRequestId(), "header.requestId"),
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getNodeId(), "header.nodeId"));
    }
    private LoginResponse session(AuthResponseDto value) { return LoginResponse.newBuilder().setAccessToken(value.getAccessToken()).setRefreshToken(value.getRefreshToken()).setExpiresIn(value.getExpiresIn()).build(); }
}
