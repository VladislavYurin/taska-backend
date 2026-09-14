package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadRequest;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadRequestBody;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlRequest;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlRequestBody;
import ru.taska.api.auth.profile.v1.DeleteMyAvatarRequest;
import ru.taska.api.auth.profile.v1.DeleteMyAvatarRequestBody;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlRequest;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlRequestBody;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AvatarResponseDto;
import ru.taska.domain.dto.ConfirmAvatarUploadRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlResponseDto;
import ru.taska.domain.dto.GetAvatarDownloadUrlResponseDto;
import ru.taska.mapper.UserProfileMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * Реактивный gRPC-клиент для взаимодействия с микросервисом аутентификации (AuthService).
 * <p>
 * Класс инкапсулирует вызовы удаленных процедур через {@link ReactorAuthServiceGrpc.ReactorAuthServiceStub},
 * управляет динамическими таймаутами (Deadlines) для каждого запроса и координирует маппинг
 * данных между REST DTO и gRPC Protobuf моделями.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcUserProfileServiceClient {

    private final ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub;
    private final UserProfileMapper profileMapper;
    private final GrpcClientProperties properties;


    public Mono<AvatarResponseDto> confirmAvatarUpload(
            Mono<ConfirmAvatarUploadRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling confirmAvatarUpload", context.requestId());
        return request
                .flatMap(requestDto->
                        dynamicStub().confirmAvatarUpload(
                                ConfirmAvatarUploadRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                ConfirmAvatarUploadRequestBody.newBuilder()
                                                        .setObjectKey(requestDto.getObjectKey())
                                                        .setFileName(requestDto.getFileName())
                                                        .setContentType(requestDto.getContentType())
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .map(profileMapper::toRestAvatarResponse);
    }

    public Mono<CreateAvatarUploadUrlResponseDto> createAvatarUploadUrl(
            Mono<CreateAvatarUploadUrlRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling createAvatarUploadUrl", context.requestId());

        return request
                .flatMap(requestDto->
                        dynamicStub().createAvatarUploadUrl(
                                CreateAvatarUploadUrlRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                CreateAvatarUploadUrlRequestBody.newBuilder()
                                                        .setContentType(requestDto.getContentType())
                                                        .setSizeBytes(requestDto.getSizeBytes())
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .map(profileMapper::toRestCreateAvatarUploadUrlResponse);
    }

    public Mono<Void> deleteMyAvatar(
            GatewayContext context
    ) {
        log.debug("[{}] Calling deleteMyAvatar", context.requestId());

        return dynamicStub().deleteMyAvatar(
                DeleteMyAvatarRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                DeleteMyAvatarRequestBody.newBuilder()
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        )
        .then();
    }

    public Mono<GetAvatarDownloadUrlResponseDto> getAvatarDownloadUrl(
            UUID userId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling getAvatarDownloadUrl", context.requestId());

        return dynamicStub().getAvatarDownloadUrl(
                        GetAvatarDownloadUrlRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(
                                        GetAvatarDownloadUrlRequestBody.newBuilder()
                                                .setUserId(userId.toString())
                                                .build()
                                )
                                .build()
                )
                .map(profileMapper::toRestGetAvatarDownloadUrlResponse);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorProfileServiceGrpc.ReactorProfileServiceStub dynamicStub() {
        return profileServiceStub.withDeadlineAfter(
                properties.authService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }

}
