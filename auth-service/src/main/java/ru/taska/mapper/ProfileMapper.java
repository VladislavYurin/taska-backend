package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadResponse;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlResponse;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse;
import ru.taska.api.auth.profile.v1.GetUserProfileResponse;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.entity.UserAvatar;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.UUID;

@Component
public class ProfileMapper {

    public UserAvatar toUserAvatar(UUID userId, String objectKey, String fileName, String contentType, long sizeBytes) {
        UserAvatar avatar = new UserAvatar();
        avatar.setUserId(userId);
        avatar.setObjectKey(objectKey);
        avatar.setFileName(fileName);
        avatar.setContentType(contentType);
        avatar.setSizeBytes(sizeBytes);
        return avatar;
    }

    public ConfirmAvatarUploadResponse toConfirmAvatarUploadResponse(AvatarDto dto) {
        return ConfirmAvatarUploadResponse.newBuilder()
                .setAvatar(toProto(dto))
                .build();
    }

    public AvatarDto toAvatarDto(UserAvatar avatar, String downloadUrl) {
        return AvatarDto.builder()
                .id(avatar.getId())
                .userId(avatar.getUserId())
                .objectKey(avatar.getObjectKey())
                .fileName(avatar.getFileName())
                .contentType(avatar.getContentType())
                .sizeBytes(avatar.getSizeBytes())
                .createdAt(avatar.getCreatedAt())
                .downloadUrl(downloadUrl)
                .build();
    }

    public GetUserProfileResponse toProto(UserProfileDto dto) {
        GetUserProfileResponse.Builder builder = GetUserProfileResponse.newBuilder();
        if (dto.getAvatar() != null) {
            builder.setAvatar(toProto(dto.getAvatar()));
        }
        return builder.build();
    }

    public CreateAvatarUploadUrlResponse toCreateAvatarUploadUrlResponse(PresignedUploadResult result) {
        return CreateAvatarUploadUrlResponse.newBuilder()
                .setUploadUrl(result.url())
                .setObjectKey(result.objectKey())
                .build();
    }

    public GetAvatarDownloadUrlResponse toGetAvatarDownloadUrlResponse(String url) {
        return GetAvatarDownloadUrlResponse.newBuilder()
                .setUrl(url)
                .build();
    }

    public AvatarResponse toProto(AvatarDto dto) {
        AvatarResponse.Builder builder = AvatarResponse.newBuilder()
                .setId(dto.getId().toString())
                .setUserId(dto.getUserId().toString())
                .setObjectKey(dto.getObjectKey())
                .setFileName(dto.getFileName())
                .setContentType(dto.getContentType())
                .setSizeBytes(dto.getSizeBytes())
                .setDownloadUrl(dto.getDownloadUrl())
                .setCreatedAt(Timestamp.newBuilder()
                        .setSeconds(dto.getCreatedAt().getEpochSecond())
                        .setNanos(dto.getCreatedAt().getNano())
                        .build());

        return builder.build();
    }
}
