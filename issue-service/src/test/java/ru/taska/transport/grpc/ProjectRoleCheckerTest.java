package ru.taska.transport.grpc;

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
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;

import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectRoleCheckerTest {

    @Mock
    private GrpcProjectServiceClient client;

    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private ProjectRoleChecker projectRoleChecker;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

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
    void checkProjectRole_shouldCompleteSuccessfully_whenRoleIsAllowed() {
        var response = CheckProjectMemberRoleResponse.newBuilder()
                .setRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_MEMBER)
                .setIsMember(true)
                .setProjectExists(true)
                .build();

        Mockito.when(client.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID)
                )
                .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_MEMBER))
                .thenReturn(ProjectRole.MEMBER);

        StepVerifier.create(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES)
                )
                .verifyComplete();

        Mockito.verify(client).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID
        );
    }

    @Test
    @DisplayName("Бросает исключение, если проект не найден")
    void checkProjectRole_shouldThrowException_whenProjectNotExists() {
        var response = CheckProjectMemberRoleResponse.newBuilder()
                .setRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_MEMBER)
                .setProjectExists(false)
                .build();

        Mockito.when(client.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID)
                )
                .thenReturn(Mono.just(response));

        StepVerifier.create(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES)
                )
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(client).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID
        );
    }

    @Test
    @DisplayName("Бросает исключение, если пользователь не является участником проекта")
    void checkProjectRole_shouldThrowException_whenUserNotProjectMember() {
        var result = CheckProjectMemberRoleResponse.newBuilder()
                .setIsMember(false)
                .setProjectExists(true)
                .build();

        Mockito.when(client.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID)
                )
                .thenReturn(Mono.just(result));

        StepVerifier.create(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES)
                )
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        Mockito.verify(client).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID
        );
    }

    @Test
    @DisplayName("Бросает исключение, если роль пользователя не разрешена")
    void checkProjectRole_shouldThrowException_whenRoleNotAllowed() {
        var response = CheckProjectMemberRoleResponse.newBuilder()
                .setRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_VIEWER)
                .setIsMember(true)
                .setProjectExists(true)
                .build();

        Mockito.when(client.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID)
                )
                .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_VIEWER))
                .thenReturn(ProjectRole.VIEWER);

        StepVerifier.create(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID, ALLOWED_ROLES)
                )
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        Mockito.verify(client).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, USER_ID
        );
    }
}
