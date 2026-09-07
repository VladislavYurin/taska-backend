package ru.taska.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadRequest;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadResponse;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlRequest;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlResponse;
import com.google.protobuf.Empty;
import ru.taska.api.auth.profile.v1.DeleteMyAvatarRequest;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlRequest;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse;
import ru.taska.api.auth.profile.v1.GetUserProfileRequest;
import ru.taska.api.auth.profile.v1.GetUserProfileResponse;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;

/**
 * Адаптер для проксирования ProfileGrpcService (для работы @TrackMetrics).
 */
@GrpcService
@RequiredArgsConstructor
public class ProfileGrpcServiceAdapter extends ReactorProfileServiceGrpc.ProfileServiceImplBase {

    private final ProfileGrpcService profileGrpcService;

    @Override
    public Mono<ConfirmAvatarUploadResponse> confirmAvatarUpload(Mono<ConfirmAvatarUploadRequest> request) {
        return profileGrpcService.confirmAvatarUpload(request)
                .transform(GrpcExceptionHandler.withErrorHandling("confirmAvatarUpload"));
    }

    @Override
    public Mono<CreateAvatarUploadUrlResponse> createAvatarUploadUrl(Mono<CreateAvatarUploadUrlRequest> request) {
        return profileGrpcService.createAvatarUploadUrl(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createAvatarUploadUrl"));
    }

    @Override
    public Mono<GetAvatarDownloadUrlResponse> getAvatarDownloadUrl(Mono<GetAvatarDownloadUrlRequest> request) {
        return profileGrpcService.getAvatarDownloadUrl(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getAvatarDownloadUrl"));
    }

    @Override
    public Mono<Empty> deleteMyAvatar(Mono<DeleteMyAvatarRequest> request) {
        return profileGrpcService.deleteMyAvatar(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteMyAvatar"));
    }

    @Override
    public Mono<GetUserProfileResponse> getUserProfile(Mono<GetUserProfileRequest> request) {
        return profileGrpcService.getUserProfile(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getUserProfile"));
    }
}
