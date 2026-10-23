package ru.taska.transport.grpc.project;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.domain.dto.ProjectRole;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProjectArchiveAuthorizationTest {
    private final GrpcProjectServiceClient client = mock(GrpcProjectServiceClient.class);
    private final ProjectRoleChecker checker = new ProjectRoleChecker(client, new ru.taska.mapper.IssueMapper(new tools.jackson.databind.ObjectMapper()));
    private final UUID project=UUID.randomUUID(), actor=UUID.randomUUID();
    private final CheckProjectMemberRoleResponse archived = CheckProjectMemberRoleResponse.newBuilder()
            .setProjectExists(true).setIsMember(true).setRole(ru.taska.api.project.v1.ProjectRole.PROJECT_ROLE_VIEWER)
            .setProjectArchived(true).build();

    @Test
    void archivedProjectRemainsReadableButWritesHaveStableReason() {
        when(client.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(archived));
        StepVerifier.create(checker.checkProjectRole("r", "n", project, actor, Set.of(ProjectRole.VIEWER))).verifyComplete();
        StepVerifier.create(checker.checkProjectWriteRole("r", "n", project, actor, Set.of(ProjectRole.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(((DomainException)e).getDetails()).containsEntry("reason", "PROJECT_ARCHIVED")).verify();
    }

    @Test
    void nonMemberDoesNotLearnArchiveState() {
        when(client.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(archived.toBuilder().setIsMember(false).build()));
        StepVerifier.create(checker.checkProjectWriteRole("r", "n", project, actor, Set.of(ProjectRole.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(((DomainException)e).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED)).verify();
    }

    @Test
    void oldServerCanServeReadsButCannotAuthorizeWrites() {
        when(client.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(archived.toBuilder().clearProjectArchived().build()));
        StepVerifier.create(checker.checkProjectRole("r", "n", project, actor, Set.of(ProjectRole.VIEWER))).verifyComplete();
        StepVerifier.create(checker.checkProjectWriteRole("r", "n", project, actor, Set.of(ProjectRole.VIEWER)))
                .expectErrorSatisfies(e -> assertThat(((DomainException)e).getStatus()).isEqualTo(DomainStatus.UNAVAILABLE)).verify();
    }

    @Test
    void activeProjectStillChecksAllowedRole() {
        when(client.checkProjectRole(any(), any(), any(), any())).thenReturn(Mono.just(archived.toBuilder().setProjectArchived(false).build()));
        StepVerifier.create(checker.checkProjectWriteRole("r", "n", project, actor, Set.of(ProjectRole.ADMIN)))
                .expectErrorSatisfies(e -> assertThat(((DomainException)e).getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED)).verify();
        StepVerifier.create(checker.checkProjectWriteRole("r", "n", project, actor, Set.of(ProjectRole.VIEWER))).verifyComplete();
    }
}
