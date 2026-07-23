package ru.taska.mapper;


import com.google.protobuf.Timestamp;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectRole;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

public class ProjectMapperTest {

    private final ProjectMapper mapper = new ProjectMapper();

    private static final String PROJECT_ID = "6d774efa-57d8-4ae0-a27e-2984d1dfbbf6";
    private static final String USER_ID = "d221b01d-9c5b-4c3b-b3be-b5502f9d1a12";
    private static final String MEMBER_ID = "7adceb90-d1ea-4e32-bda7-9c9bd8aa8ef5";
    private static final Instant NOW = Instant.now();

    private Timestamp createTimestamp(){
        return Timestamp.newBuilder()
                .setSeconds(NOW.getEpochSecond())
                .setNanos(NOW.getNano())
                .build();
    }

    // ========== ProjectResponse -> ProjectResponseDto ==========

    @Test
    @DisplayName("Должен корректно преобразовать ProjectResponse в ProjectResponseDto")
    void toRestProjectResponse_shouldCorrectMapsAllFields() {
        // given
        var timestamp = createTimestamp();
        var proto = ProjectResponse.newBuilder()
                .setId(PROJECT_ID)
                .setProjectKey("TASKA")
                .setName("Taska Platform")
                .setCreatedBy(USER_ID)
                .setCreatedAt(timestamp)
                .setUpdatedAt(timestamp)
                .build();

        // when
        var result = mapper.toRestProjectResponse(proto);

        // then
        var expectedDateTime = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        Assertions.assertThat(result.getId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(result.getProjectKey()).isEqualTo("TASKA");
        Assertions.assertThat(result.getName()).isEqualTo("Taska Platform");
        Assertions.assertThat(result.getCreatedBy()).isEqualTo(USER_ID);
        Assertions.assertThat(result.getCreatedAt()).isEqualTo(expectedDateTime);
        Assertions.assertThat(result.getUpdatedAt()).isEqualTo(expectedDateTime);
        Assertions.assertThat(result.getArchivedAt()).isNull();
    }

    @Test
    @DisplayName("Должен корректно преобразовать ProjectResponse с archivedAt")
    void toRestProjectResponse_shouldMapArchivedAt() {
        // given
        var timestamp = createTimestamp();
        var proto = ProjectResponse.newBuilder()
                .setId(PROJECT_ID)
                .setProjectKey("TASKA")
                .setName("Taska Platform")
                .setCreatedBy(USER_ID)
                .setCreatedAt(timestamp)
                .setUpdatedAt(timestamp)
                .setArchivedAt(timestamp)
                .build();

        // when
        var result = mapper.toRestProjectResponse(proto);

        // then
        var expectedDateTime = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        Assertions.assertThat(result.getArchivedAt()).isEqualTo(expectedDateTime);
    }

    // ========== ListMyProjectsResponse -> ListMyProjectResponseDto ==========

    @Test
    @DisplayName("Должен корректно преобразовать ListMyProjectsResponse в ListMyProjectResponseDto")
    void toRestListMyProjectsResponse_shouldCorrectMapsAllFields() {
        // given
        var timestamp = createTimestamp();
        var project1 = ProjectResponse.newBuilder()
                .setId(PROJECT_ID)
                .setProjectKey("TASKA")
                .setName("Taska Platform")
                .setCreatedBy(USER_ID)
                .setCreatedAt(timestamp)
                .setUpdatedAt(timestamp)
                .build();

        var project2 = ProjectResponse.newBuilder()
                .setId("12345678-1234-1234-1234-123456789012")
                .setProjectKey("TEST")
                .setName("Test Project")
                .setCreatedBy(USER_ID)
                .setCreatedAt(timestamp)
                .setUpdatedAt(timestamp)
                .build();

        var proto = ListMyProjectsResponse.newBuilder()
                .addProjectResponse(project1)
                .addProjectResponse(project2)
                .build();

        // when
        var result = mapper.toRestListMyProjectsResponse(proto);

        // then
        Assertions.assertThat(result.getItems()).hasSize(2);
        Assertions.assertThat(result.getItems().get(0).getProjectKey()).isEqualTo("TASKA");
        Assertions.assertThat(result.getItems().get(1).getProjectKey()).isEqualTo("TEST");
    }

    @Test
    @DisplayName("Должен вернуть пустой список, если проектов нет")
    void toRestListMyProjectsResponse_shouldReturnEmptyList_whenNoProjects() {
        // given
        var proto = ListMyProjectsResponse.newBuilder().build();

        // when
        var result = mapper.toRestListMyProjectsResponse(proto);

        // then
        Assertions.assertThat(result.getItems()).isEmpty();
    }

    // ========== AddProjectMemberResponse → ProjectMemberResponseDto ==========

    @Test
    @DisplayName("Должен корректно преобразовать AddProjectMemberResponse в ProjectMemberResponseDto")
    void toRestAddProjectMemberRequest_shouldCorrectMapsAllFields() {
        // given
        var proto = AddProjectMemberResponse.newBuilder()
                .setProjectId(PROJECT_ID)
                .setAddedMemberId(MEMBER_ID)
                .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                .build();

        // when
        var result = mapper.toRestAddProjectMemberResponse(proto);

        // then
        Assertions.assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(result.getUserId()).isEqualTo(MEMBER_ID);
        Assertions.assertThat(result.getRole()).isEqualTo("MEMBER");
    }

    // ========== ChangeProjectMemberRoleResponse → ProjectMemberResponseDto ==========

    @Test
    @DisplayName("Должен корректно преобразовать ChangeProjectMemberRoleResponse в ProjectMemberResponseDto")
    void toRestChangeProjectMemberRoleRequest_shouldCorrectMapsAllFields() {
        // given
        var proto = ChangeProjectMemberRoleResponse.newBuilder()
                .setProjectId(PROJECT_ID)
                .setChangedMemberId(MEMBER_ID)
                .setRole(ProjectRole.PROJECT_ROLE_ADMIN)
                .build();

        // when
        var result = mapper.toRestChangeProjectMemberRoleResponse(proto);

        // then
        Assertions.assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(result.getUserId()).isEqualTo(MEMBER_ID);
        Assertions.assertThat(result.getRole()).isEqualTo("ADMIN");
    }

    // ========== Enum преобразования ==========

    @ParameterizedTest
    @MethodSource("restRoleToGrpcRoleArguments")
    @DisplayName("Должен корректно преобразовать REST роль в gRPC роль")
    void toGrpcProjectRole_shouldCorrectMapsAllFields(String restRole, ProjectRole expected) {

        // when
        var result = mapper.toGrpcProjectRole(restRole);

        // then
        Assertions.assertThat(result).isEqualTo(expected);
    }
    private static Stream<Arguments> restRoleToGrpcRoleArguments() {
        return Stream.of(
                Arguments.of("ADMIN", ProjectRole.PROJECT_ROLE_ADMIN),
                Arguments.of("MEMBER", ProjectRole.PROJECT_ROLE_MEMBER),
                Arguments.of("VIEWER", ProjectRole.PROJECT_ROLE_VIEWER)
        );
    }

    @ParameterizedTest
    @MethodSource("grpcRoleToRestRoleArguments")
    @DisplayName("Должен корректно преобразовать gRPC роль в REST роль")
    void toRestProjectRole_shouldCorrectMapsAllFields(ProjectRole grpcRole, String expected) {
        // when
        var result = mapper.toRestProjectRole(grpcRole);

        // then
        Assertions.assertThat(result).isEqualTo(expected);
    }
    private static Stream<Arguments> grpcRoleToRestRoleArguments() {
        return Stream.of(
                Arguments.of(ProjectRole.PROJECT_ROLE_ADMIN, "ADMIN"),
                Arguments.of(ProjectRole.PROJECT_ROLE_MEMBER, "MEMBER"),
                Arguments.of(ProjectRole.PROJECT_ROLE_VIEWER, "VIEWER")
        );
    }

    @Test
    @DisplayName("Должен выбрасывать исключение для неизвестной REST роли")
    void toGrpcProjectRole_shouldThrowException_whenUnknownRole() {
        // when & then
        Assertions.assertThatThrownBy(() -> mapper.toGrpcProjectRole("AbstractRole"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Invalid project role");
    }

    @Test
    @DisplayName("Должен возвращать UNSPECIFIED для null")
    void toGrpcProjectRole_shouldReturnUnspecified_whenNull() {
        // when
        var result = mapper.toGrpcProjectRole( null);

        // then
        Assertions.assertThat(result).isEqualTo(ProjectRole.PROJECT_ROLE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Должен выбрасывать исключение для UNSPECIFIED gRPC роли")
    void toRestProjectRole_shouldThrowException_whenUnspecified() {
        // when & then
        Assertions.assertThatThrownBy(() -> mapper.toRestProjectRole(ProjectRole.PROJECT_ROLE_UNSPECIFIED))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Invalid project role");
    }

    // ========== Timestamp → OffsetDateTime ==========

    @Test
    @DisplayName("Должен корректно преобразовывать Timestamp в OffsetDateTime")
    void toOffsetDateTime_shouldCorrectConvertTimestamp() {
        // given
        var timestamp = Timestamp.newBuilder()
                .setSeconds(1)
                .build();

        // when
        var result = mapper.toOffsetDateTime(timestamp);

        // then
        Assertions.assertThat(result).isEqualTo(OffsetDateTime.parse("1970-01-01T00:00:01Z"));
    }

    @Test
    @DisplayName("Должен возвращать null для null Timestamp")
    void toOffsetDateTime_shouldReturnNull_whenTimestampIsNull() {
        // when
        var result = mapper.toOffsetDateTime(null);

        // then
        Assertions.assertThat(result).isNull();
    }
}
