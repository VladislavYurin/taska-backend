package ru.taska.mapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;

import java.time.Instant;
import java.util.UUID;

class ProfileMapperTest {

    private ProfileMapper profileMapper;

    @BeforeEach
    void setUp() {
        profileMapper = new ProfileMapper();
    }

    @Test
    @DisplayName("toProto: должен корректно маппить все поля при полностью заполненном DTO")
    void toProto_whenDtoIsFullyPopulated_shouldMapAllFields() {
        UUID userId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();

        AvatarDto avatarDto = AvatarDto.builder()
                .id(avatarId)
                .userId(userId)
                .objectKey("avatars/user.png")
                .fileName("user.png")
                .contentType("image/png")
                .sizeBytes(1024L)
                .downloadUrl("https://example.com/avatar.png")
                .createdAt(Instant.now())
                .build();

        UserDetailsDto dto = UserDetailsDto.builder()
                .userId(userId)
                .displayName("John Doe")
                .email("john@example.com")
                .avatar(avatarDto)
                .build();

        UserDetails proto = profileMapper.toProto(dto);

        Assertions.assertNotNull(proto);
        Assertions.assertEquals(userId.toString(), proto.getUserId());
        Assertions.assertEquals("John Doe", proto.getDisplayName());
        Assertions.assertEquals("john@example.com", proto.getEmail());
        Assertions.assertTrue(proto.hasAvatar());
        Assertions.assertEquals(avatarId.toString(), proto.getAvatar().getId());
    }

    @Test
    @DisplayName("toProto: аватар без createdAt (и без строковых полей) — ответ собран, аватар есть, createdAt не задан")
    void toProto_whenAvatarHasNoCreatedAt_shouldMapAvatarWithoutCreatedAt() {
        UUID userId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();

        AvatarDto avatarDto = AvatarDto.builder()
                .id(avatarId)
                .userId(userId)
                .objectKey("avatars/user.png")
                .downloadUrl("https://example.com/avatar.png")
                .fileName(null)
                .contentType(null)
                .sizeBytes(null)
                .createdAt(null)
                .build();

        UserDetailsDto dto = UserDetailsDto.builder()
                .userId(userId)
                .displayName("John Doe")
                .email("john@example.com")
                .avatar(avatarDto)
                .build();

        UserDetails proto = Assertions.assertDoesNotThrow(() -> profileMapper.toProto(dto));

        Assertions.assertTrue(proto.hasAvatar());
        Assertions.assertEquals(avatarId.toString(), proto.getAvatar().getId());
        Assertions.assertEquals(userId.toString(), proto.getAvatar().getUserId());
        Assertions.assertEquals("avatars/user.png", proto.getAvatar().getObjectKey());
        Assertions.assertEquals("https://example.com/avatar.png", proto.getAvatar().getDownloadUrl());
        Assertions.assertEquals("", proto.getAvatar().getFileName());
        Assertions.assertEquals("", proto.getAvatar().getContentType());
        Assertions.assertEquals(0L, proto.getAvatar().getSizeBytes());
        Assertions.assertFalse(proto.getAvatar().hasCreatedAt());
    }

    @Test
    @DisplayName("toProto: пользователь без аватара — avatar не задан")
    void toProto_whenUserHasNoAvatar_shouldNotSetAvatar() {
        UUID userId = UUID.randomUUID();

        UserDetailsDto dto = UserDetailsDto.builder()
                .userId(userId)
                .displayName("John Doe")
                .email("john@example.com")
                .avatar(null)
                .build();

        UserDetails proto = profileMapper.toProto(dto);

        Assertions.assertEquals(userId.toString(), proto.getUserId());
        Assertions.assertEquals("John Doe", proto.getDisplayName());
        Assertions.assertFalse(proto.hasAvatar());
    }

    @Test
    @DisplayName("toProto(AvatarDto): полностью заполненный аватар — все поля, включая createdAt, на месте")
    void toProtoAvatar_whenFullyPopulated_shouldMapAllFields() {
        UUID userId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-05T12:30:00Z");

        AvatarDto avatarDto = AvatarDto.builder()
                .id(avatarId)
                .userId(userId)
                .objectKey("avatars/user.png")
                .fileName("user.png")
                .contentType("image/png")
                .sizeBytes(1024L)
                .downloadUrl("https://example.com/avatar.png")
                .createdAt(createdAt)
                .build();

        var proto = profileMapper.toProto(avatarDto);

        Assertions.assertEquals(avatarId.toString(), proto.getId());
        Assertions.assertEquals(userId.toString(), proto.getUserId());
        Assertions.assertEquals("user.png", proto.getFileName());
        Assertions.assertEquals("image/png", proto.getContentType());
        Assertions.assertEquals(1024L, proto.getSizeBytes());
        Assertions.assertTrue(proto.hasCreatedAt());
        Assertions.assertEquals(createdAt.getEpochSecond(), proto.getCreatedAt().getSeconds());
    }

    @Test
    @DisplayName("toProto: должен безопасно обрабатывать null-поля в DTO и не бросать NullPointerException")
    void toProto_whenDtoHasNullFields_shouldNotThrowNpe() {
        UUID userId = UUID.randomUUID();

        UserDetailsDto dto = UserDetailsDto.builder()
                .userId(userId)
                .build();

        Assertions.assertDoesNotThrow(() -> {
            UserDetails proto = profileMapper.toProto(dto);

            Assertions.assertNotNull(proto);
            Assertions.assertEquals(userId.toString(), proto.getUserId());
            Assertions.assertEquals("", proto.getDisplayName());
            Assertions.assertEquals("", proto.getEmail());
            Assertions.assertFalse(proto.hasAvatar());
        });
    }
}