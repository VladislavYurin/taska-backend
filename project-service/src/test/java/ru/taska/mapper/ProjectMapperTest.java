package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.AvatarDto;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectContextDto;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Тесты сборки {@link ProjectContextResponse}: участники без аватара / имени / e-mail
 * не должны ронять маппинг (TAS-254).
 */
class ProjectMapperTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private ProjectMapper projectMapper;

    @BeforeEach
    void setUp() throws Exception {
        projectMapper = Mappers.getMapper(ProjectMapper.class);
        // componentModel = SPRING: зависимость ProjectMemberMapper инжектится полем, вне контекста подставляем вручную
        ProjectMemberMapper projectMemberMapper = Mappers.getMapper(ProjectMemberMapper.class);
        for (Field field : projectMapper.getClass().getDeclaredFields()) {
            if (ProjectMemberMapper.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(projectMapper, projectMemberMapper);
            }
        }
    }

    private ProjectContextDto contextWithMember(ProjectMemberDetailsDto member) {
        ProjectCheckMembershipDto project = ProjectCheckMembershipDto.builder()
                .project_id(PROJECT_ID)
                .project_key("TAS")
                .name("Taska")
                .created_by(USER_ID)
                .created_at(Instant.parse("2026-10-01T10:00:00Z"))
                .updated_at(Instant.parse("2026-10-01T10:00:00Z"))
                .user_id(USER_ID)
                .role(ProjectRole.ADMIN)
                .build();
        return new ProjectContextDto(project, List.of(), List.of(), List.of(member));
    }

    @Test
    @DisplayName("toProjectContextResponse: участник без аватара, имени, e-mail и addedAt — ответ собран, avatar не задан")
    void toProjectContextResponse_whenMemberHasNoAvatar_shouldBuildResponseWithoutAvatar() {
        ProjectMemberDetailsDto member = ProjectMemberDetailsDto.builder()
                .userId(USER_ID)
                .role(ProjectRole.MEMBER)
                .displayName(null)
                .email(null)
                .avatar(null)
                .addedAt(null)
                .build();

        ProjectContextResponse response = projectMapper.toProjectContextResponse(contextWithMember(member));

        Assertions.assertThat(response.getProject().getId()).isEqualTo(PROJECT_ID.toString());
        Assertions.assertThat(response.getMembersList()).hasSize(1);
        ProjectMemberDetailsResponse memberResponse = response.getMembers(0);
        Assertions.assertThat(memberResponse.getUserId()).isEqualTo(USER_ID.toString());
        Assertions.assertThat(memberResponse.getRole()).isEqualTo(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_MEMBER);
        Assertions.assertThat(memberResponse.getDisplayName()).isEmpty();
        Assertions.assertThat(memberResponse.getEmail()).isEmpty();
        Assertions.assertThat(memberResponse.hasAvatar()).isFalse();
        Assertions.assertThat(memberResponse.hasAddedAt()).isFalse();
    }

    @Test
    @DisplayName("toProjectContextResponse: участник с аватаром — avatar на месте, downloadUrl заполнен")
    void toProjectContextResponse_whenMemberHasAvatar_shouldMapAvatar() {
        UUID avatarId = UUID.randomUUID();
        Instant addedAt = Instant.parse("2026-10-05T12:30:00Z");
        AvatarDto avatar = AvatarDto.builder()
                .id(avatarId)
                .userId(USER_ID)
                .objectKey("avatars/user.png")
                .fileName("user.png")
                .contentType("image/png")
                .sizeBytes(1024L)
                .downloadUrl("https://storage.example.com/avatars/user.png")
                .build();
        ProjectMemberDetailsDto member = ProjectMemberDetailsDto.builder()
                .userId(USER_ID)
                .role(ProjectRole.ADMIN)
                .displayName("John Doe")
                .email("john@example.com")
                .avatar(avatar)
                .addedAt(addedAt)
                .build();

        ProjectContextResponse response = projectMapper.toProjectContextResponse(contextWithMember(member));

        Assertions.assertThat(response.getMembersList()).hasSize(1);
        ProjectMemberDetailsResponse memberResponse = response.getMembers(0);
        Assertions.assertThat(memberResponse.getDisplayName()).isEqualTo("John Doe");
        Assertions.assertThat(memberResponse.getEmail()).isEqualTo("john@example.com");
        Assertions.assertThat(memberResponse.hasAvatar()).isTrue();
        Assertions.assertThat(memberResponse.getAvatar().getId()).isEqualTo(avatarId.toString());
        Assertions.assertThat(memberResponse.getAvatar().getObjectKey()).isEqualTo("avatars/user.png");
        Assertions.assertThat(memberResponse.getAvatar().getFileName()).isEqualTo("user.png");
        Assertions.assertThat(memberResponse.getAvatar().getContentType()).isEqualTo("image/png");
        Assertions.assertThat(memberResponse.getAvatar().getSizeBytes()).isEqualTo(1024L);
        Assertions.assertThat(memberResponse.getAvatar().getDownloadUrl())
                .isEqualTo("https://storage.example.com/avatars/user.png");
        Assertions.assertThat(memberResponse.hasAddedAt()).isTrue();
        Assertions.assertThat(memberResponse.getAddedAt().getSeconds()).isEqualTo(addedAt.getEpochSecond());
    }
}
