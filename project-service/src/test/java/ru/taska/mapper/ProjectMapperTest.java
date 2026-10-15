package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.api.project.v1.WorkflowDataResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.AvatarDto;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectContextDto;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowStatusDto;
import ru.taska.domain.dto.projectContext.WorkflowTransitionDto;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Тесты сборки {@link ProjectContextResponse}: участники без аватара / имени / e-mail
 * не должны ронять маппинг (TAS-254); статусы и переходы workflow доходят до ответа (TAS-261).
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

    private ProjectContextDto contextWithWorkflow(ProjectWorkflowDto workflow) {
        ProjectContextDto base = contextWithMember(ProjectMemberDetailsDto.builder()
                .userId(USER_ID)
                .role(ProjectRole.ADMIN)
                .build());
        return new ProjectContextDto(base.project(), List.of(), List.of(workflow), base.members());
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

    @Test
    @DisplayName("toProjectContextResponse: workflow — statuses и transitions на месте, а не пустые списки")
    void toProjectContextResponse_whenWorkflowHasStatusesAndTransitions_shouldMapBoth() {
        WorkflowDto workflow = WorkflowDto.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .name("Default workflow")
                .version(1)
                .createdAt(Instant.parse("2026-07-30T08:00:00Z"))
                .updatedAt(Instant.parse("2026-07-30T08:00:00Z"))
                .statuses(List.of(
                        WorkflowStatusDto.builder()
                                .id("22222222-2222-2222-2222-222222222222")
                                .statusKey("TODO").name("To Do").category("TODO").sortOrder(1)
                                .build(),
                        WorkflowStatusDto.builder()
                                .id("33333333-3333-3333-3333-333333333333")
                                .statusKey("IN_PROGRESS").name("In Progress").category("IN_PROGRESS").sortOrder(2)
                                .build()))
                .transitions(List.of(
                        WorkflowTransitionDto.builder()
                                .id("55555555-5555-5555-5555-555555555555")
                                .fromStatusId("22222222-2222-2222-2222-222222222222")
                                .toStatusId("33333333-3333-3333-3333-333333333333")
                                .name("Start Progress").sortOrder(1)
                                .build()))
                .build();

        ProjectContextResponse response = projectMapper.toProjectContextResponse(
                contextWithWorkflow(new ProjectWorkflowDto("TASK", workflow)));

        Assertions.assertThat(response.getWorkflowsList()).hasSize(1);
        Assertions.assertThat(response.getWorkflows(0).getIssueType()).isEqualTo("TASK");
        WorkflowDataResponse workflowResponse = response.getWorkflows(0).getWorkflow();
        Assertions.assertThat(workflowResponse.getId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        Assertions.assertThat(workflowResponse.getStatusesList())
                .extracting(status -> status.getStatusKey() + "=" + status.getId())
                .containsExactly(
                        "TODO=22222222-2222-2222-2222-222222222222",
                        "IN_PROGRESS=33333333-3333-3333-3333-333333333333");
        Assertions.assertThat(workflowResponse.getTransitionsList()).hasSize(1);
        Assertions.assertThat(workflowResponse.getTransitions(0).getId()).isEqualTo("55555555-5555-5555-5555-555555555555");
        Assertions.assertThat(workflowResponse.getTransitions(0).getFromStatusId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        Assertions.assertThat(workflowResponse.getTransitions(0).getToStatusId()).isEqualTo("33333333-3333-3333-3333-333333333333");
        Assertions.assertThat(workflowResponse.getTransitions(0).getName()).isEqualTo("Start Progress");
    }
}
