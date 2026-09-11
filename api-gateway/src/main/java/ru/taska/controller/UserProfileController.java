package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.UserProfileApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AvatarResponseDto;
import ru.taska.domain.dto.ConfirmAvatarUploadRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlResponseDto;
import ru.taska.domain.dto.GetAvatarDownloadUrlResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserProfileController implements UserProfileApi {

    private final GatewayRequestExecutor executor;
    private final GrpcUserProfileServiceClient grpcClient;

    @Override
    public Mono<ResponseEntity<AvatarResponseDto>> confirmAvatarUpload(
            Mono<ConfirmAvatarUploadRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                grpcClient.confirmAvatarUpload(requestDto,context)
                        .map(ResponseEntity::ok)
        );
    }

    @Override
    public Mono<ResponseEntity<CreateAvatarUploadUrlResponseDto>> createAvatarUploadUrl(
            Mono<CreateAvatarUploadUrlRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange,EndpointSecurity.PROTECTED,context->
                grpcClient.createAvatarUploadUrl(requestDto,context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        );
    }

    @Override
    public Mono<ResponseEntity<Void>> deleteMyAvatar(
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange,EndpointSecurity.PROTECTED,context ->
                grpcClient.deleteMyAvatar(context)
                        .thenReturn(ResponseEntity.noContent().build())
        );
    }

    @Override
    public Mono<ResponseEntity<GetAvatarDownloadUrlResponseDto>> getAvatarDownloadUrl(
            UUID userId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange,EndpointSecurity.PROTECTED,context ->
                grpcClient.getAvatarDownloadUrl(userId,context)
                        .map(ResponseEntity::ok)
        );
    }
}
