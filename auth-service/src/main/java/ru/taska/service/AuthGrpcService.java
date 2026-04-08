package ru.taska.service;

import ru.taska.grpc.LoginRequest;
import ru.taska.grpc.LoginResponse;
import ru.taska.grpc.RefreshRequest;

public interface AuthGrpcService {
    void login(LoginRequest request,
                      io.grpc.stub.StreamObserver<LoginResponse> responseObserver);

    void refresh(RefreshRequest request,
                 io.grpc.stub.StreamObserver<ru.taska.grpc.RefreshResponse> responseObserver);
}
