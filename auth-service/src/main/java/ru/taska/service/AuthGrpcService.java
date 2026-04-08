package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.taska.grpc.AuthServiceGrpc;
import ru.taska.grpc.LoginRequest;
import ru.taska.grpc.LoginResponse;
import ru.taska.grpc.RefreshRequest;
import ru.taska.grpc.RefreshResponse;

@GrpcService
@RequiredArgsConstructor
@Slf4j
public class AuthGrpcService  extends AuthServiceGrpc.AuthServiceImplBase {

    private final AuthService authService;

    @Override
    public void login(LoginRequest request,
                      io.grpc.stub.StreamObserver<LoginResponse> responseObserver) {
        log.info("gRPC login request for email: {}", request.getEmail());

        authService.login(request.getEmail(), request.getPassword())
                .subscribe(
                        response -> {
                            LoginResponse.Builder builder =
                                    LoginResponse.newBuilder()
                                            .setAccessToken(response.getAccessToken())
                                            .setExpiresIn(response.getExpiresIn());

                            if (response.getRefreshToken() != null) {
                                builder.setRefreshToken(response.getRefreshToken());
                            }

                            responseObserver.onNext(builder.build());
                            responseObserver.onCompleted();
                        },
                        error -> {
                            log.error("Login failed: {}", error.getMessage());
                            responseObserver.onError(
                                    io.grpc.Status.UNAUTHENTICATED
                                            .withDescription(error.getMessage())
                                            .asRuntimeException()
                            );
                        }
                );
    }

    @Override
    public void refresh(RefreshRequest request,
                        io.grpc.stub.StreamObserver<ru.taska.grpc.RefreshResponse> responseObserver) {
        log.info("gRPC refresh request");

        authService.refresh(request.getRefreshToken())
                .subscribe(
                        response -> {
                            RefreshResponse.Builder builder =
                                    RefreshResponse.newBuilder()
                                            .setAccessToken(response.getAccessToken())
                                            .setExpiresIn(response.getExpiresIn());

                            if (response.getRefreshToken() != null) {
                                builder.setRefreshToken(response.getRefreshToken());
                            }

                            responseObserver.onNext(builder.build());
                            responseObserver.onCompleted();
                        },
                        error -> {
                            log.error("Refresh failed: {}", error.getMessage());
                            responseObserver.onError(
                                    io.grpc.Status.UNAUTHENTICATED
                                            .withDescription(error.getMessage())
                                            .asRuntimeException()
                            );
                        }
                );
    }
}