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