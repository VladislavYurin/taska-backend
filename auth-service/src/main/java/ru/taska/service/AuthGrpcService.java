package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.taska.grpc.AuthServiceGrpc;

@GrpcService
@RequiredArgsConstructor
@Slf4j
public class AuthGrpcService  extends AuthServiceGrpc.AuthServiceImplBase {

    private final AuthService authService;

    @Override
    public void login(ru.taska.grpc.LoginRequest request,
                      io.grpc.stub.StreamObserver<ru.taska.grpc.LoginResponse> responseObserver) {
        log.info("gRPC login request for email: {}", request.getEmail());

        authService.login(request.getEmail(), request.getPassword())
                .subscribe(
                        response -> {
                            ru.taska.grpc.LoginResponse.Builder builder =
                                    ru.taska.grpc.LoginResponse.newBuilder()
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
    public void refresh(ru.taska.grpc.RefreshRequest request,
                        io.grpc.stub.StreamObserver<ru.taska.grpc.RefreshResponse> responseObserver) {
        log.info("gRPC refresh request");

        authService.refresh(request.getRefreshToken())
                .subscribe(
                        response -> {
                            ru.taska.grpc.RefreshResponse.Builder builder =
                                    ru.taska.grpc.RefreshResponse.newBuilder()
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