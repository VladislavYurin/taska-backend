package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadResponse;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlResponse;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse;
import ru.taska.domain.dto.AvatarResponseDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlResponseDto;
import ru.taska.domain.dto.GetAvatarDownloadUrlResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class UserProfileMapper {
    public AvatarResponseDto toRestAvatarResponse(
            ConfirmAvatarUploadResponse protoResponse
    ) {
        AvatarResponseDto responseDto = new AvatarResponseDto();
        responseDto.setId(UUID.fromString(protoResponse.getAvatar().getId()));
        responseDto.setUserId(UUID.fromString(protoResponse.getAvatar().getUserId()));
        responseDto.setObjectKey(protoResponse.getAvatar().getObjectKey());
        responseDto.setFileName(protoResponse.getAvatar().getFileName());
        responseDto.setContentType(protoResponse.getAvatar().getContentType());
        responseDto.setSizeBytes(protoResponse.getAvatar().getSizeBytes());
        responseDto.setCreatedAt(toOffsetDateTime(protoResponse.getAvatar().getCreatedAt()));
        responseDto.setDownloadUrl(protoResponse.getAvatar().getDownloadUrl());
        return responseDto;
    }

    public CreateAvatarUploadUrlResponseDto toRestCreateAvatarUploadUrlResponse(
            CreateAvatarUploadUrlResponse protoResponse
    ) {
        CreateAvatarUploadUrlResponseDto responseDto = new CreateAvatarUploadUrlResponseDto();
        responseDto.setUploadUrl(protoResponse.getUploadUrl());
        responseDto.setObjectKey(protoResponse.getObjectKey());
        responseDto.setExpiresIn(protoResponse.getExpiresIn());
        return responseDto;
    }

    public GetAvatarDownloadUrlResponseDto toRestGetAvatarDownloadUrlResponse(
            GetAvatarDownloadUrlResponse protoResponse
    ) {
        GetAvatarDownloadUrlResponseDto responseDto = new GetAvatarDownloadUrlResponseDto();
        responseDto.setUrl(protoResponse.hasUrl() ? protoResponse.getUrl():null);
        return responseDto;
    }

    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
