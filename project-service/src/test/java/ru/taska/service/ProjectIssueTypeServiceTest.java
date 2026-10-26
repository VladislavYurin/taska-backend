package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import nullable.NullableField;
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
        StepVerifier.create(service.create(project, actor, "REQUEST", " Заявка ", "teal", "headset", null, 40))
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
        StepVerifier.create(service.create(project, actor, "REQUEST", "Request", "blue", "headset", null, null))
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
        StepVerifier.create(service.create(project, actor, "TASK", "Task", "blue", null, "square", 10))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.ALREADY_EXISTS)).verify();
        verify(repository, never()).syncLegacySettings(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "request", "A-B", " TASK", "TASK ", "TOO_LONG_12345678901234567890123456789"})
    void invalidCodesAreRejectedBeforeWriting(String code) {
        StepVerifier.create(service.create(project, actor, code, "Request", "blue", "headset", null, 1))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.INVALID_ARGUMENT)).verify();
        verifyNoInteractions(repository);
    }

    @Test
    void patchPreservesOmittedFieldsAndImmutableCode() {
        authorize(ProjectRole.ADMIN);
        when(repository.find(project, "BUG")).thenReturn(Mono.just(type("BUG")));
        when(repository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.update(project, actor, "BUG", "Инцидент", null, NullableField.absent(), null, null))
                .assertNext(t -> {
                    assertThat(t.code()).isEqualTo("BUG");
                    assertThat(t.name()).isEqualTo("Инцидент");
                    assertThat(t.color()).isEqualTo("blue");
                    assertThat(t.sortOrder()).isEqualTo(10);
                }).verifyComplete();
    }

    @Test
    void createWithoutShapeOrIconIsPlainSquare() {
        authorize(ProjectRole.ADMIN);
        when(repository.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.create(project, actor, "REQUEST", "Request", "teal", null, null, null))
                .assertNext(type -> {
                    assertThat(type.shape()).isEqualTo("square");
                    assertThat(type.icon()).isNull();
                    assertThat(type.sortOrder()).isEqualTo(100);
                }).verifyComplete();
    }

    @Test
    void createKeepsChosenShapeAndIcon() {
        authorize(ProjectRole.ADMIN);
        when(repository.insert(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
        StepVerifier.create(service.create(project, actor, "MILESTONE", "Milestone", "amber", "flag", "diamond", 40))
                .assertNext(type -> {
                    assertThat(type.shape()).isEqualTo("diamond");
                    assertThat(type.icon()).isEqualTo("flag");
                }).verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hexagon", "Square", "SQUARE"})
    void invalidShapeIsRefusedOnCreateAndPatchWithoutWriting(String shape) {
        StepVerifier.create(service.create(project, actor, "REQUEST", "Request", "blue", null, shape, 1))
                .expectErrorSatisfies(e -> assertInvalidLook(e)).verify();
        authorize(ProjectRole.ADMIN);
        when(repository.find(project, "BUG")).thenReturn(Mono.just(type("BUG")));
        StepVerifier.create(service.update(project, actor, "BUG", null, null, NullableField.absent(), shape, null))
                .expectErrorSatisfies(e -> assertInvalidLook(e)).verify();
        verify(repository, never()).insert(any());
        verify(repository, never()).update(any());
    }

    @Test
    void invalidIconIsStillRefused() {
        StepVerifier.create(service.create(project, actor, "REQUEST", "Request", "blue", "rocket", null, 1))
                .expectErrorSatisfies(e -> assertInvalidLook(e)).verify();
        verifyNoInteractions(repository);
    }

    @Test
    void patchShapeChangesOnlyTheShape() {
        patchable();
        StepVerifier.create(service.update(project, actor, "BUG", null, null, NullableField.absent(), "circle", null))
                .assertNext(t -> {
                    assertThat(t.shape()).isEqualTo("circle");
                    assertThat(t.icon()).isEqualTo("bug");
                    assertThat(t.name()).isEqualTo("Name");
                    assertThat(t.color()).isEqualTo("blue");
                }).verifyComplete();
    }

    @Test
    void patchExplicitNullIconRemovesIt() {
        patchable();
        StepVerifier.create(service.update(project, actor, "BUG", null, null, NullableField.of(null), null, null))
                .assertNext(t -> {
                    assertThat(t.icon()).isNull();
                    assertThat(t.shape()).isEqualTo("square");
                }).verifyComplete();
    }

    @Test
    void patchAbsentIconKeepsItAndPresentIconReplacesIt() {
        patchable();
        StepVerifier.create(service.update(project, actor, "BUG", "Defect", null, NullableField.absent(), null, null))
                .assertNext(t -> assertThat(t.icon()).isEqualTo("bug")).verifyComplete();
        StepVerifier.create(service.update(project, actor, "BUG", null, null, NullableField.of("flag"), null, null))
                .assertNext(t -> assertThat(t.icon()).isEqualTo("flag")).verifyComplete();
    }

    @Test
    void emptyPatchIsStillRefusedBeforeAnyRead() {
        StepVerifier.create(service.update(project, actor, "BUG", null, null, NullableField.absent(), null, null))
                .expectErrorSatisfies(e -> {
                    assertStatus(e, DomainStatus.INVALID_ARGUMENT);
                    assertThat(e).hasMessage("At least one field is required");
                }).verify();
        verifyNoInteractions(repository);
    }

    @Test
    void usedTypeCannotBeDeletedAndIncludesIssueCount() {
        authorizeDelete(3);
        when(issues.countIssueTypeUsage(project, "BUG", "r", "n")).thenReturn(Mono.just(7L));
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n"))
                .expectErrorSatisfies(e -> {
                    assertStatus(e, DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) e).getDetails()).containsEntry("issueCount", "7").containsEntry("reason", "ISSUE_TYPE_IN_USE");
                }).verify();
        verify(repository, never()).delete(any(), any());
    }

    @Test
    void lastTypeCannotBeDeletedEvenWhenUnused() {
        authorizeDelete(1);
        StepVerifier.create(service.delete(project, actor, "BUG", "r", "n"))
                .expectErrorSatisfies(e -> {
                    assertStatus(e, DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException)e).getDetails()).containsEntry("reason", "LAST_ISSUE_TYPE").doesNotContainKey("issueCount");
                }).verify();
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

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"MEMBER", "VIEWER", "UNSPECIFIED"})
    void reorderRequiresAdminBeforeReadingRegistry(ProjectRole role) {
        authorize(role);
        StepVerifier.create(service.reorder(project, actor, java.util.List.of("TASK", "BUG", "STORY")))
                .expectErrorSatisfies(error -> assertStatus(error, DomainStatus.PERMISSION_DENIED)).verify();
        verify(repository, never()).list(any());
        verify(repository, never()).reorder(any(), any());
    }

    @Test
    void duplicateCodesCannotBeSilentlyCollapsed() {
        StepVerifier.create(service.reorder(project, actor, java.util.List.of("TASK", "BUG", "BUG")))
                .expectErrorSatisfies(error -> assertStatus(error, DomainStatus.INVALID_ARGUMENT)).verify();
        verifyNoInteractions(repository);
    }

    @Test
    void wellFormedStalePermutationIsConflictAndNeverWrites() {
        authorize(ProjectRole.ADMIN);
        when(repository.list(project)).thenReturn(Flux.just(type("TASK"), type("BUG"), type("STORY")));
        StepVerifier.create(service.reorder(project, actor, java.util.List.of("TASK", "BUG")))
                .expectErrorSatisfies(error -> {
                    assertStatus(error, DomainStatus.ABORTED);
                    assertThat(((DomainException)error).getDetails()).containsExactlyEntriesOf(java.util.Map.of("reason", "ISSUE_TYPES_CHANGED"));
                }).verify();
        verify(repository, never()).reorder(any(), any());
        verify(repository, never()).syncLegacySettings(any(), any());
    }

    private void patchable() {
        authorize(ProjectRole.ADMIN);
        when(repository.find(project, "BUG")).thenReturn(Mono.just(type("BUG")));
        when(repository.update(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(repository.syncLegacySettings(project, actor)).thenReturn(Mono.empty());
    }

    private void assertInvalidLook(Throwable e) {
        assertStatus(e, DomainStatus.INVALID_ARGUMENT);
        assertThat(e).hasMessage("Invalid issue type name, color, icon, shape or sortOrder");
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
        return new ProjectIssueType(project, code, "Name", "blue", "bug", "square", 10, null, null);
    }

    private void assertStatus(Throwable e, DomainStatus status) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).getStatus()).isEqualTo(status);
    }
}
