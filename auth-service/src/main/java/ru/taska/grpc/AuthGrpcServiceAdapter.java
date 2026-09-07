package ru.taska.grpc;


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
