package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.oauth.v1.*;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;
import ru.taska.mapper.AuthMapper;
import ru.taska.mapper.MappingUtils;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class GrpcOAuthClient {
    private final ReactorOAuthServiceGrpc.ReactorOAuthServiceStub oauth;
    private final AuthMapper mapper;
    public Mono<StartOAuthResponse> start(String provider, String returnTo, String clientIp, GatewayContext context) {
        return oauth.withDeadlineAfter(5, TimeUnit.SECONDS).startOAuth(StartOAuthRequest.newBuilder()
                .setHeader(MappingUtils.buildGrpcHeader(context)).setClientIp(value(clientIp)).setProvider(provider).setReturnTo(returnTo).build());
    }
    public Mono<String> callback(String provider, String state, String browser, String code, String error, String user, String clientIp, GatewayContext context) {
        return oauth.withDeadlineAfter(25, TimeUnit.SECONDS).completeOAuth(CompleteOAuthRequest.newBuilder().setHeader(MappingUtils.buildGrpcHeader(context)).setClientIp(value(clientIp))
                .setProvider(provider).setState(value(state)).setBrowserToken(value(browser)).setCode(value(code)).setError(value(error)).setAppleUser(value(user)).build())
                .map(CompleteOAuthResponse::getRedirectUrl);
    }
    public Mono<OAuthExchangeResponseDto> exchange(OAuthExchangeRequestDto request, String clientIp, GatewayContext context) {
        return oauth.withDeadlineAfter(10, TimeUnit.SECONDS).exchangeOAuth(ExchangeOAuthRequest.newBuilder().setHeader(MappingUtils.buildGrpcHeader(context)).setClientIp(value(clientIp)).setCode(value(request.getCode())).build())
                .map(response -> {
                    var result = new OAuthExchangeResponseDto().status(OAuthExchangeResponseDto.StatusEnum.fromValue(response.getStatus())).returnTo(response.getReturnTo());
                    if (response.hasSession()) result.setSession(mapper.toLoginRestResponse(response.getSession()));
                    if (response.hasSignup()) {
                        var signup = response.getSignup();
                        result.setSignup(new OAuthSignupStateDto().signupToken(signup.getSignupToken()).email(signup.getEmail())
                                .provider(OAuthSignupStateDto.ProviderEnum.fromValue(signup.getProvider()))
                                .suggestedDisplayName(signup.hasSuggestedDisplayName() ? signup.getSuggestedDisplayName() : null));
                    }
                    return result;
                });
    }
    public Mono<LoginResponseDto> signup(OAuthSignupRequestDto request, String clientIp, GatewayContext context) {
        return Mono.defer(() -> oauth.withDeadlineAfter(10, TimeUnit.SECONDS).signupOAuth(SignupOAuthRequest.newBuilder().setHeader(MappingUtils.buildGrpcHeader(context)).setClientIp(value(clientIp))
                .setSignupToken(value(request.getSignupToken())).setDisplayName(value(request.getDisplayName())).build())).map(mapper::toLoginRestResponse);
    }
    private String value(String value) { return value == null ? "" : value; }
}
