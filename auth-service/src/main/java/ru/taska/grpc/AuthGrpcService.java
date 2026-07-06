package ru.taska.grpc;

import com.google.protobuf.Empty;
import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.service.AuthService;
import ru.taska.util.DataMaskingHelper;
import validator.GrpcRequestValidators;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class AuthGrpcService extends ReactorAuthServiceGrpc.AuthServiceImplBase {

    private final AuthService authService;

    @Override
    public Mono<LoginResponse> login(Mono<LoginRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getEmail(), "body.email"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getPassword(), "body.password")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String email = t.getT3();
                    String password = t.getT4();

                    log.info("[{}][{}] Login request for email: {}", requestId, nodeId, DataMaskingHelper.maskEmail(email));

                    return authService.login(email, password)
                            .doOnSuccess(response -> log.debug("[{}][{}] Login successful for email: {}",
                                    requestId, nodeId, DataMaskingHelper.maskEmail(email)))
                            .doOnError(error -> log.warn("[{}][{}] Login failed for email: {}; {}",
                                    requestId, nodeId, DataMaskingHelper.maskEmail(email), error.getMessage()));
                })
                .map(response -> LoginResponse.newBuilder()
                        .setAccessToken(response.getAccessToken())
                        .setRefreshToken(response.getRefreshToken())
                        .setExpiresIn(response.getExpiresIn())
                        .build())
                .transform(GrpcExceptionHandler.withErrorHandling("login"));
    }

    @Override
    public Mono<RefreshResponse> refresh(Mono<RefreshRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getRefreshToken(), "body.refreshToken")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String refreshToken = t.getT3();

                    log.info("[{}][{}] Refresh request", requestId, nodeId);

                    return authService.refresh(refreshToken)
                            .doOnSuccess(response -> log.debug("[{}][{}] Refresh successful", requestId, nodeId))
                            .doOnError(error -> log.warn("[{}][{}] Refresh failed: {}", requestId, nodeId, error.getMessage()));
                })
                .map(response -> RefreshResponse.newBuilder()
                        .setAccessToken(response.getAccessToken())
                        .setRefreshToken(response.getRefreshToken() != null ? response.getRefreshToken() : "")
                        .setExpiresIn(response.getExpiresIn())
                        .build())
                .transform(GrpcExceptionHandler.withErrorHandling("refresh"));
    }

    @Override
    public Mono<Empty> setPasswordByToken(Mono<SetPasswordByTokenRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getToken(), "body.token"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getNewPassword(), "body.newPassword")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String token = t.getT3();
                    String newPassword = t.getT4();

                    log.info("[{}][{}] Set new password request", requestId, nodeId);
                    return authService.setPasswordByToken(requestId, token, newPassword)
                            .doOnSuccess(response -> log.debug("[{}][{}] Set new password successful", requestId, nodeId))
                            .doOnError(error -> log.warn("[{}][{}] Set new password failed: {}", requestId, nodeId, error.getMessage()));
                })
                .thenReturn(Empty.getDefaultInstance())
                .transform(GrpcExceptionHandler.withErrorHandling("setPasswordByToken"));
    }

    @Override
    public Mono<ValidateAccessTokenResponse> validateAccessToken(Mono<ValidateAccessTokenRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getAccessToken(), "body.accessToken")
                ))

                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    String accessToken = t.getT3();

                    log.info("[{}][{}] Validating access token", requestId, nodeId);

                    return authService.validateAccessToken(accessToken)
                            .doOnSuccess(userContext ->
                                    log.info("[{}][{}] Access token validated successfully for user: {}",
                                            requestId, nodeId, userContext.getUserId()))
                            .doOnError(error ->
                                    log.warn("[{}][{}] Access token validation failed: {}",
                                            requestId, nodeId, error.getMessage())
                            );
                })
                .map(userContext -> ValidateAccessTokenResponse.newBuilder()
                        .setUserContext(userContext)
                        .build()
                )
                .transform(GrpcExceptionHandler.withErrorHandling("validateAccessToken"));
    }
}