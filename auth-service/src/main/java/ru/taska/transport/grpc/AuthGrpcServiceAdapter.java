package ru.taska.transport.grpc;


import com.google.protobuf.Empty;
import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;

/**
 * Класс адаптер. Нужен для проксирования GRPC сервиса (для работы кастомной аннотации @TrackMetrics)
 */
@GrpcService
@RequiredArgsConstructor
public class AuthGrpcServiceAdapter extends ReactorAuthServiceGrpc.AuthServiceImplBase{

    private final AuthGrpcService authGrpcService;
    private final ru.taska.service.EmailSignupService signup;
    @Override
    public Mono<Empty> signup(Mono<ru.taska.api.auth.v1.SignupRequest> request) {
        return request.flatMap(req -> Mono.when(
                validator.GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                validator.GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"))
                .then(Mono.defer(() -> signup.signup(req.getEmail(), req.getDisplayName(), req.getClientIp(), req.getHeader().getRequestId()))))
                .thenReturn(Empty.getDefaultInstance()).transform(GrpcExceptionHandler.withErrorHandling("signup"));
    }
    @Override
    public Mono<ru.taska.api.auth.v1.AuthMethodsResponse> getAuthMethods(Mono<Empty> request) {
        return request.map(ignored -> ru.taska.api.auth.v1.AuthMethodsResponse.newBuilder()
                .setSignupEnabled(signup.enabled()).addAllProviders(signup.providers()).build())
                .transform(GrpcExceptionHandler.withErrorHandling("getAuthMethods"));
    }


    @Override
    public Mono<ru.taska.api.auth.v1.PreviewInvitationResponse> previewInvitation(
            Mono<ru.taska.api.auth.v1.PreviewInvitationRequest> request) {
        return authGrpcService.previewInvitation(request).transform(GrpcExceptionHandler.withErrorHandling("previewInvitation"));
    }

    @Override
    public Mono<LoginResponse> login(Mono<LoginRequest> request){
        return authGrpcService.login(request)
                .transform(GrpcExceptionHandler.withErrorHandling("login"));
    }

    @Override
    public Mono<RefreshResponse> refresh(Mono<RefreshRequest> request){
        return authGrpcService.refresh(request)
                .transform(GrpcExceptionHandler.withErrorHandling("refresh"));
    }

    @Override
    public Mono<Empty> setPasswordByToken(Mono<SetPasswordByTokenRequest> request){
        return authGrpcService.setPasswordByToken(request)
                .transform(GrpcExceptionHandler.withErrorHandling("setPasswordByToken"));
    }

    @Override
    public Mono<ValidateAccessTokenResponse> validateAccessToken(Mono<ValidateAccessTokenRequest> request) {
        return authGrpcService.validateAccessToken(request)
                .transform(GrpcExceptionHandler.withErrorHandling("validateAccessToken"));
    }

}
