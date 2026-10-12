package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadResponse;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlResponse;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse;
import ru.taska.api.auth.profile.v1.GetUserProfileResponse;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.entity.UserAvatar;
import ru.taska.storage.dto.PresignedUploadResult;

import java.util.Objects;
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
                .setExpiresIn(result.expiresIn())
                .build();
    }

    public GetAvatarDownloadUrlResponse toGetAvatarDownloadUrlResponse(String url) {
        return GetAvatarDownloadUrlResponse.newBuilder()
                .setUrl(url)
                .build();
    }

    /**
     * Маппинг {@link AvatarDto} → {@link AvatarResponse}.
     *
     * <p>Protobuf-сеттеры не принимают {@code null}, поэтому каждое поле ставится только если оно заполнено.
     * Пустое поле в DTO (например, {@code createdAt} у аватара из {@code findUsersWithAvatars})
     * не должно ронять весь ответ.</p>
     */
    public AvatarResponse toProto(AvatarDto dto) {
        AvatarResponse.Builder builder = AvatarResponse.newBuilder();

        if (dto.getId() != null) {
            builder.setId(dto.getId().toString());
        }
        if (dto.getUserId() != null) {
            builder.setUserId(dto.getUserId().toString());
        }
        if (dto.getObjectKey() != null) {
            builder.setObjectKey(dto.getObjectKey());
        }
        if (dto.getFileName() != null) {
            builder.setFileName(dto.getFileName());
        }
        if (dto.getContentType() != null) {
            builder.setContentType(dto.getContentType());
        }
        if (dto.getSizeBytes() != null) {
            builder.setSizeBytes(dto.getSizeBytes());
        }
        if (dto.getDownloadUrl() != null) {
            builder.setDownloadUrl(dto.getDownloadUrl());
        }
        if (dto.getCreatedAt() != null) {
            builder.setCreatedAt(Timestamp.newBuilder()
                    .setSeconds(dto.getCreatedAt().getEpochSecond())
                    .setNanos(dto.getCreatedAt().getNano())
                    .build());
        }

        return builder.build();
    }

    public UserDetails toProto(UserDetailsDto dto) {
        if (dto == null) {
            return null;
        }

        var builder = UserDetails.newBuilder()
                .setUserId(dto.userId() != null ? dto.userId().toString() : "")
                .setDisplayName(Objects.requireNonNullElse(dto.displayName(), ""))
                .setEmail(Objects.requireNonNullElse(dto.email(), ""));

        if (dto.avatar() != null && dto.avatar().getId() != null) {
            builder.setAvatar(toProto(dto.avatar()));
        }

        return builder.build();
    }
}
