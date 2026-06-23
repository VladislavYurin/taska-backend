package ru.taska.grpc;

import com.google.protobuf.Empty;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.PasswordByTokenRequest;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.service.AuthService;
import ru.taska.util.DataMaskingHelper;
import validator.GrpcRequestValidators;

@Slf4j
@Service
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
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
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
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
    public Mono<Empty> setPasswordByToken(Mono<PasswordByTokenRequest> request) {
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
                    return authService.setPasswordByToken(token, newPassword)
                            .doOnSuccess(response -> log.debug("[{}][{}] Set new password successful", requestId, nodeId))
                            .doOnError(error -> log.warn("[{}][{}] Set new password failed: {}", requestId, nodeId, error.getMessage()));
                })
                .thenReturn(Empty.getDefaultInstance())
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
                }
    }