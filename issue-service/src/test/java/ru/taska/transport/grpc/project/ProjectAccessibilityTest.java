package ru.taska.transport.grpc.project;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;

@ExtendWith(MockitoExtension.class)
class ProjectAccessibilityTest {

    @Mock
    private GrpcProjectServiceClient client;

    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private ProjectAccessibility projectAccessibility;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final Timestamp ARCHIVED_AT = Timestamp.newBuilder()
                                                          .setSeconds(Instant.parse("2025-01-01T00:00:00Z").getEpochSecond())
                                                          .build();

    private static Set<ProjectRole> ALLOWED_ROLES;

    @BeforeAll
    static void setUp() {
        ALLOWED_ROLES = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER
        );
    }

    @Test
    @DisplayName("Успешная проверка роли пользователя")
    void checkProjectRole_shouldCompleteSuccessfully_whenAccessIsAllowed() {
        var response = ProjectResponse.newBuilder()
                                      .setCurrentUserRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_ADMIN)
                                      .build();

        Mockito.when(client.getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID))
               .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainRole(Mockito.any()))
               .thenReturn(ProjectRole.ADMIN);

        StepVerifier.create(projectAccessibility.check(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES))
                    .expectNext(response)
                    .verifyComplete();

        Mockito.verify(client).getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("Бросает исключение, если проект не найден")
    void check_shouldThrowNotFound_whenGrpcNotFound() {

        Mockito.when(client.getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID))
               .thenReturn(Mono.error(Status.NOT_FOUND
                                              .withDescription("Project not found")
                                              .asRuntimeException()));

        StepVerifier.create(projectAccessibility.check(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertThat(error).isInstanceOf(DomainException.class);
                        DomainException ex = (DomainException) error;
                        Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                        Assertions.assertThat(ex.getMessage()).isEqualTo("Project not found");
                    })
                    .verify();

        Mockito.verify(client).getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("Бросает исключение, если пользователь не является участником проекта")
    void checkProjectRole_shouldThrowException_whenUserNotProjectMember() {
        Mockito.when(client.getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID))
               .thenReturn(Mono.error(Status.PERMISSION_DENIED
                                              .withDescription("You don't have access to this project")
                                              .asRuntimeException()));

        StepVerifier.create(projectAccessibility.check(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertThat(error).isInstanceOf(DomainException.class);
                        DomainException ex = (DomainException) error;
                        Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                        Assertions.assertThat(ex.getMessage()).isEqualTo("You don't have access to this project");
                    })
                    .verify();

        Mockito.verify(client).getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("Бросает исключение, если роль пользователя не разрешена")
    void checkProjectRole_shouldThrowException_whenAccessNotAllowed() {
        var response = ProjectResponse.newBuilder()
                                      .setCurrentUserRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_VIEWER)
                                      .build();

        Mockito.when(client.getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID))
               .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_VIEWER))
               .thenReturn(ProjectRole.VIEWER);

        StepVerifier.create(projectAccessibility.check(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertThat(error).isInstanceOf(DomainException.class);
                        Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                    })
                    .verify();

        Mockito.verify(client).getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("Бросает исключение, если роль пользователя не разрешена")
    void checkProjectRole_shouldThrowException_whenProjectArchived() {
        var response = ProjectResponse.newBuilder()
                                      .setArchivedAt(ARCHIVED_AT)
                                      .build();

        Mockito.when(client.getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID))
               .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainRole(Mockito.any()))
               .thenReturn(ProjectRole.ADMIN);

        StepVerifier.create(projectAccessibility.check(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertThat(error).isInstanceOf(DomainException.class);
                        Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.PROJECT_ARCHIVED);
                    })
                    .verify();

        Mockito.verify(client).getProject(REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID);
    }
}
