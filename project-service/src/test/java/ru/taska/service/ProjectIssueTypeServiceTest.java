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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectIssueTypeRepository;

@ExtendWith(MockitoExtension.class)
class ProjectIssueTypeServiceTest {
    @Mock private ProjectWriteGuard writeGuard;
    @org.junit.jupiter.api.BeforeEach
    void writable() {
        lenient().when(writeGuard.check(any(), any())).thenReturn(Mono.empty());
        lenient().when(operations.requireFree(any())).thenReturn(Mono.empty());
        lenient().when(operations.reserve(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(creationJournal.request(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(creationJournal.insert(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(repository.find(any(), any())).thenReturn(Mono.empty());
        lenient().when(repository.list(project)).thenReturn(Flux.just(type("TASK")));
        creation = new IssueTypeCreationTransactions(repository, operations, creationJournal, writeGuard,
                tools.jackson.databind.json.JsonMapper.builder().build());
    }
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
    @Mock ProjectIssueTypeRepository repository;
    @Mock ProjectService projects;
    @Mock ru.taska.repository.ProjectTypeOperationRepository operations;
    @Mock ru.taska.repository.IssueTypeCreationRepository creationJournal;
    private IssueTypeCreationTransactions creation;
    @InjectMocks ProjectIssueTypeService service;

    @Test
    void creationDraftKeepsCustomMetadataHiddenUntilWorkflowBindingIsConfirmed() {
        authorize(ProjectRole.ADMIN);
        StepVerifier.create(create("REQUEST", " Заявка ", "teal", "headset", null, 40))
                .assertNext(type -> {
                    assertThat(type.code()).isEqualTo("REQUEST");
                    assertThat(type.name()).isEqualTo("Заявка");
                    assertThat(type.sortOrder()).isEqualTo(40);
                }).verifyComplete();
        var captured = org.mockito.ArgumentCaptor.forClass(ru.taska.domain.IssueTypeCreation.class);
        verify(creationJournal).insert(captured.capture());
        assertThat(captured.getValue().state()).isEqualTo("PENDING");
        assertThat(captured.getValue().firstTypeCode()).isEqualTo("TASK");
        assertThat(captured.getValue().firstTypeGeneration()).isEqualTo(type("TASK").generation());
        assertThat(captured.getValue().desiredWorkflowId()).isNull();
        verify(repository, never()).insert(any());
        verify(repository, never()).insertWithGeneration(any());
        verify(repository, never()).syncLegacySettings(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ProjectRole.class, names = {"MEMBER", "VIEWER", "UNSPECIFIED"})
    void onlyProjectAdminMayWrite(ProjectRole role) {
        authorize(role);
        StepVerifier.create(create("REQUEST", "Request", "blue", "headset", null, null))
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
        when(repository.find(project, "TASK")).thenReturn(Mono.just(type("TASK")));
        StepVerifier.create(create("TASK", "Task", "blue", null, "square", 10))
                .expectErrorSatisfies(e -> assertStatus(e, DomainStatus.ALREADY_EXISTS)).verify();
        verify(repository, never()).syncLegacySettings(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "request", "A-B", " TASK", "TASK ", "TOO_LONG_12345678901234567890123456789"})
    void invalidCodesAreRejectedBeforeWriting(String code) {
        StepVerifier.create(create(code, "Request", "blue", "headset", null, 1))
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
        StepVerifier.create(create("REQUEST", "Request", "teal", null, null, null))
                .assertNext(type -> {
                    assertThat(type.shape()).isEqualTo("square");
                    assertThat(type.icon()).isNull();
                    assertThat(type.sortOrder()).isEqualTo(100);
                }).verifyComplete();
    }

    @Test
    void createKeepsChosenShapeAndIcon() {
        authorize(ProjectRole.ADMIN);
        StepVerifier.create(create("MILESTONE", "Milestone", "amber", "flag", "diamond", 40))
                .assertNext(type -> {
                    assertThat(type.shape()).isEqualTo("diamond");
                    assertThat(type.icon()).isEqualTo("flag");
                }).verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hexagon", "Square", "SQUARE"})
    void invalidShapeIsRefusedOnCreateAndPatchWithoutWriting(String shape) {
        StepVerifier.create(create("REQUEST", "Request", "blue", null, shape, 1))
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
        StepVerifier.create(create("REQUEST", "Request", "blue", "rocket", null, 1))
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

    private Mono<ProjectIssueType> create(String code, String name, String color, String icon, String shape, Integer sortOrder) {
        return creation.begin(project, actor, "metadata-unit", "project-service", code, name, color, icon, shape,
                sortOrder, null, ru.taska.domain.ActionMetadata.NONE).map(ru.taska.domain.IssueTypeCreation::type);
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

    private ProjectIssueType type(String code) {
        return new ProjectIssueType(project, code, "Name", "blue", "bug", "square", 10, null, null,
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void assertStatus(Throwable e, DomainStatus status) {
        assertThat(e).isInstanceOf(DomainException.class);
        assertThat(((DomainException) e).getStatus()).isEqualTo(status);
    }
}
