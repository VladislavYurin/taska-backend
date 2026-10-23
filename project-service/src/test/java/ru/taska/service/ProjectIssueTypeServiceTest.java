package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectIssueTypeRepository;
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;

@ExtendWith(MockitoExtension.class)
class ProjectIssueTypeServiceTest {
    @Mock private ProjectWriteGuard writeGuard;
    @org.junit.jupiter.api.BeforeEach
    void writable() { lenient().when(writeGuard.check(any(), any())).thenReturn(reactor.core.publisher.Mono.empty()); }
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
    @Mock ProjectIssueTypeRepository repository;
    @Mock ProjectService projects;
    @Mock GrpcIssueServiceClient issues;
    @InjectMocks ProjectIssueTypeService service;

    @Test
    void createsCustomTypeAndMirrorsLegacySettings() {
        authorize(ProjectRole.ADMIN);
        when(repository.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.create(project, actor, "REQUEST", " Заявка ", "teal", "headset", 40))
                .assertNext(type -> {
                    assertThat(type.code()).isEqualTo("REQUEST");
                    assertThat(type.name()).isEqualTo("Заявка");
                    assertThat(type.sortOrder()).isEqualTo(40);
                }).verifyComplete();
        verify(repository).syncLegacySettings(project, actor);
    }

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"MEMBER", "VIEWER", "UNSPECIFIED"})
    void onlyProjectAdminMayWrite(ProjectRole role) {
        authorize(role);
        StepVerifier.create(service.create(project, actor, "REQUEST", "Request", "blue", "headset", null))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.PERMISSION_DENIED)).verify();
        verify(repository, never()).insert(any());
    }

    @Test
    void nonMemberCannotReadRegistry() {
        when(projects.getProject("r", "n", project, actor)).thenReturn(Mono.error(
                new DomainException(DomainStatus.PERMISSION_DENIED, "Not a member")));
        StepVerifier.create(service.list(project, actor, "r", "n"))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.PERMISSION_DENIED)).verify();
        // thenMany must defer the repository read until access has been checked.
        verify(repository, never()).list(project);
    }

    @Test
    void duplicateCodeIs409() {
        authorize(ProjectRole.ADMIN);
        when(repository.insert(any())).thenReturn(Mono.error(new DuplicateKeyException("duplicate")));
        StepVerifier.create(service.create(project, actor, "TASK", "Task", "blue", "check-square", 10))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.ALREADY_EXISTS)).verify();
        verify(repository, never()).syncLegacySettings(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "request", "A-B", " TASK", "TASK ", "TOO_LONG_12345678901234567890123456789"})
    void invalidCodesAreRejectedBeforeWriting(String code) {
        StepVerifier.create(service.create(project, actor, code, "Request", "blue", "headset", 1))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.INVALID_ARGUMENT)).verify();
        verifyNoInteractions(repository);
    }

    @Test
    void patchPreservesOmittedFieldsAndImmutableCode() {
        authorize(ProjectRole.ADMIN);
        when(repository.find(project, "BUG")).thenReturn(Mono.just(type("BUG")));
        when(repository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.update(project, actor, "BUG", "Инцидент", null, null, null))
                .assertNext(t -> {
                    assertThat(t.code()).isEqualTo("BUG");
                    assertThat(t.name()).isEqualTo("Инцидент");
                    assertThat(t.color()).isEqualTo("blue");
                    assertThat(t.sortOrder()).isEqualTo(10);
                }).verifyComplete();
    }

    @Test
    void usedTypeCannotBeDeletedAndIncludesIssueCount() {
        authorizeDelete(3);
        when(issues.countIssueTypeUsage(project, "BUG", "r", "n")).thenReturn(Mono.just(7L));
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n"))
                .expectErrorSatisfies(e -> {
                    assertStatus(e, DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) e).getDetails()).containsEntry("issueCount", "7");
                }).verify();
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void lastTypeCannotBeDeletedEvenWhenUnused() {
        authorizeDelete(1);
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n"))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.FAILED_PRECONDITION)).verify();
        verifyNoInteractions(issues);
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void failureToCountIssuesDoesNotDeleteType() {
        authorizeDelete(2);
        when(issues.countIssueTypeUsage(project, "BUG", "r", "n")).thenReturn(Mono.error(
                new DomainException(DomainStatus.UNAVAILABLE, "upstream unavailable")));
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n"))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.UNAVAILABLE)).verify();
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void unusedTypeIsDeletedAndDefaultMirrorIsUpdated() {
        authorizeDelete(2);
        when(issues.countIssueTypeUsage(project, "BUG", "r", "n")).thenReturn(Mono.just(0L));
        when(repository.delete(project, "BUG")).thenReturn(Mono.empty());
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n")).verifyComplete();
        verify(repository).syncLegacySettings(project, actor);
    }

    private void authorize(ProjectRole role) {
        when(repository.lockProject(project)).thenReturn(Mono.just(project));
        when(repository.lockActorRole(project, actor)).thenReturn(Mono.just(role));
    }

    private void authorizeDelete(int count) {
        authorize(ProjectRole.ADMIN);
        when(repository.find(project, "BUG")).thenReturn(Mono.just(type("BUG")));
        when(repository.list(project)).thenReturn(Flux.range(0, count).map(i -> type("TYPE_" + i)));
    }

    private ProjectIssueType type(String code) {
        return new ProjectIssueType(project, code, "Name", "blue", "bug", 10, null, null);
    }

    private void assertStatus(Throwable e, DomainStatus status) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).getStatus()).isEqualTo(status);
    }
}
