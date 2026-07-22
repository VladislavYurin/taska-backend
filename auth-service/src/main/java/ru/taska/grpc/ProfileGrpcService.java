package ru.taska.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
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
import ru.taska.mapper.ProfileMapper;
import ru.taska.service.ProfileService;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileGrpcService {

    private final ProfileService profileService;
    private final ProfileMapper profileMapper;

    @TrackMetrics(counter = "auth-service_confirmAvatarUpload_grpc_counter",
            timer = "auth-service_confirmAvatarUpload_grpc_timer")
    public Mono<ConfirmAvatarUploadResponse> confirmAvatarUpload(Mono<ConfirmAvatarUploadRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getObjectKey(), "body.object_key"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getFileName(), "body.file_name"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getContentType(), "body.content_type"),
                        GrpcRequestValidators.requirePositiveOrInvalidArgument(
                                req.getBody().getSizeBytes(), "body.size_bytes")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID actorUserId = t.getT3();
                    String objectKey = t.getT4();
                    String fileName = t.getT5();
                    String contentType = t.getT6();
                    long sizeBytes = t.getT7();

                    log.info("[{}][{}] ConfirmAvatarUpload request: actorUserId={}, objectKey={}, contentType={}, sizeBytes={}",
                            requestId, nodeId, actorUserId, objectKey, contentType, sizeBytes);

                    return profileService.confirmAvatarUpload(actorUserId, objectKey, fileName, contentType, sizeBytes)
                            .doOnSuccess(result -> log.debug("[{}][{}] ConfirmAvatarUpload success: avatarId={}",
                                    requestId, nodeId, result.getId()))
                            .doOnError(error -> log.warn("[{}][{}] ConfirmAvatarUpload failed for actorUserId={}: {}",
                                    requestId, nodeId, actorUserId, error.getMessage()));
                })
                .map(profileMapper::toConfirmAvatarUploadResponse);
    }

    @TrackMetrics(counter = "auth-service_createAvatarUploadUrl_grpc_counter",
            timer = "auth-service_createAvatarUploadUrl_grpc_timer")
    public Mono<CreateAvatarUploadUrlResponse> createAvatarUploadUrl(Mono<CreateAvatarUploadUrlRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getContentType(), "body.content_type"),
                        GrpcRequestValidators.requirePositiveOrInvalidArgument(
                                req.getBody().getSizeBytes(), "body.size_bytes")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID actorUserId = t.getT3();
                    String contentType = t.getT4();
                    long sizeBytes = t.getT5();

                    log.info("[{}][{}] CreateAvatarUploadUrl request: actorUserId={}, contentType={}, sizeBytes={}",
                            requestId, nodeId, actorUserId, contentType, sizeBytes);

                    return profileService.createAvatarUploadUrl(actorUserId, contentType, sizeBytes)
                            .doOnSuccess(result -> log.debug("[{}][{}] CreateAvatarUploadUrl success: objectKey={}",
                                    requestId, nodeId, result.objectKey()))
                            .doOnError(error -> log.warn("[{}][{}] CreateAvatarUploadUrl failed for actorUserId={}: {}",
                                    requestId, nodeId, actorUserId, error.getMessage()));
                })
                .map(profileMapper::toCreateAvatarUploadUrlResponse);
    }

    @TrackMetrics(counter = "auth-service_getAvatarDownloadUrl_grpc_counter",
            timer = "auth-service_getAvatarDownloadUrl_grpc_timer")
    public Mono<GetAvatarDownloadUrlResponse> getAvatarDownloadUrl(Mono<GetAvatarDownloadUrlRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getUserId(), "body.user_id")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID userId = t.getT3();

                    log.info("[{}][{}] GetAvatarDownloadUrl request for userId={}", requestId, nodeId, userId);

                    return profileService.getAvatarDownloadUrl(userId)
                            .doOnSuccess(url -> log.debug("[{}][{}] GetAvatarDownloadUrl success for userId={}",
                                    requestId, nodeId, userId))
                            .doOnError(error -> log.warn("[{}][{}] GetAvatarDownloadUrl failed for userId={}: {}",
                                    requestId, nodeId, userId, error.getMessage()));
                })
                .map(profileMapper::toGetAvatarDownloadUrlResponse)
                .defaultIfEmpty(GetAvatarDownloadUrlResponse.getDefaultInstance());
    }

    @TrackMetrics(counter = "auth-service_deleteMyAvatar_grpc_counter",
            timer = "auth-service_deleteMyAvatar_grpc_timer")
    public Mono<Empty> deleteMyAvatar(Mono<DeleteMyAvatarRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID actorUserId = t.getT3();

                    log.info("[{}][{}] DeleteMyAvatar request: actorUserId={}", requestId, nodeId, actorUserId);

                    return profileService.deleteMyAvatar(actorUserId)
                            .doOnSuccess(v -> log.debug("[{}][{}] DeleteMyAvatar success for actorUserId={}",
                                    requestId, nodeId, actorUserId))
                            .doOnError(error -> log.warn("[{}][{}] DeleteMyAvatar failed for actorUserId={}: {}",
                                    requestId, nodeId, actorUserId, error.getMessage()));
                })
                .thenReturn(Empty.getDefaultInstance());
    }

    @TrackMetrics(counter = "auth-service_getUserProfile_grpc_counter",
            timer = "auth-service_getUserProfile_grpc_timer")
    public Mono<GetUserProfileResponse> getUserProfile(Mono<GetUserProfileRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getUserId(), "body.user_id")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID userId = t.getT3();

                    log.info("[{}][{}] GetUserProfile request for userId={}", requestId, nodeId, userId);

                    return profileService.getUserProfile(userId)
                            .doOnSuccess(response -> log.debug("[{}][{}] GetUserProfile success for userId={}",
                                    requestId, nodeId, userId))
                            .doOnError(error -> log.warn("[{}][{}] GetUserProfile failed for userId={}: {}",
                                    requestId, nodeId, userId, error.getMessage()));
                })
                .map(profileMapper::toProto);
    }
}
