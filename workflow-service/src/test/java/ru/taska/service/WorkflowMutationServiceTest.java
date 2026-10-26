package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.WorkflowMutation;
import ru.taska.entity.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.repository.*;
import ru.taska.service.WorkflowMutationService.Operation;
import ru.taska.transport.grpc.issue.WorkflowIssueMigrationClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

class WorkflowMutationServiceTest {
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), workflow = UUID.randomUUID();
    private final UUID defaultProject = new UUID(0, 0);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final StatusRepository statuses = mock(StatusRepository.class);
    private final TransitionRepository transitions = mock(TransitionRepository.class);
    private final WorkflowBindingRepository bindings = mock(WorkflowBindingRepository.class);
    private final WorkflowIssueMigrationClient issues = mock(WorkflowIssueMigrationClient.class);
    private final ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient = mock(ru.taska.transport.grpc.project.GrpcProjectServiceClient.class);
    private final ProjectRoleChecker roles = mock(ProjectRoleChecker.class);
    private final AtomicReference<List<StatusEntity>> statusData = new AtomicReference<>();
    private final AtomicReference<List<TransitionEntity>> transitionData = new AtomicReference<>();
    private WorkflowMutationService service;
    private WorkflowEntity entity;

    @BeforeEach
    void setup() {
        var props = new WorkflowProperties(defaultProject, new WorkflowProperties.AllowedRoles(Set.of(ProjectRole.ADMIN), Set.of(ProjectRole.ADMIN)));
        when(projectClient.listIssueTypeDetails(any(), any(), any(), any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(project, ru.taska.domain.IssueType.values())));
        var editor = new WorkflowEditService(new WorkflowCreationValidator(100, "^[A-Z][A-Z0-9_]{1,63}$"),
                new WorkflowCreationMapper(), workflows, statuses, transitions, bindings, roles, props, projectClient);
        service = new WorkflowMutationService(editor, workflows, statuses, transitions, bindings, issues, props);
        entity = WorkflowEntity.builder().id(workflow).name("Workflow").version(4).active(true).build();
        statusData.set(new ArrayList<>(List.of(status(1, "TODO", 10), status(2, "REVIEW", 20), status(3, "DONE", 30))));
        transitionData.set(new ArrayList<>(List.of(edge(11, 1, 2, 10, false), edge(12, 2, 3, 20, false),
                edge(13, 1, 3, 30, false), edge(14, 3, 1, 40, true))));
        when(roles.checkProjectWriteRole(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(workflows.findByIdForUpdate(workflow)).thenReturn(Mono.just(entity));
        when(bindings.findByWorkflowId(workflow)).thenReturn(Flux.just(binding(project)));
        when(statuses.findActiveByWorkflowId(workflow)).thenAnswer(i -> Flux.fromIterable(statusData.get()));
        when(transitions.findByWorkflowIdOrderBySortOrderAsc(workflow)).thenAnswer(i -> Flux.fromIterable(transitionData.get()));
        when(workflows.updateMetadata(eq(workflow), any(), eq(4))).thenReturn(Mono.just(1));
        when(workflows.findById(workflow)).thenReturn(Mono.just(entity.toBuilder().version(5).build()));
        when(statuses.save(any())).thenAnswer(i -> {
            StatusEntity value = i.getArgument(0);
            statusData.updateAndGet(all -> all.stream().map(s -> s.getId().equals(value.getId()) ? value : s).toList());
            return Mono.just(value);
        });
        when(transitions.save(any())).thenAnswer(i -> {
            TransitionEntity value = i.getArgument(0);
            transitionData.updateAndGet(all -> all.stream().map(t -> t.getId().equals(value.getId()) ? value : t).toList());
            return Mono.just(value);
        });
        when(transitions.delete(any(TransitionEntity.class))).thenAnswer(i -> {
            TransitionEntity value = i.getArgument(0);
            transitionData.updateAndGet(all -> all.stream().filter(t -> !t.getId().equals(value.getId())).toList());
            return Mono.empty();
        });
        when(statuses.delete(any(StatusEntity.class))).thenAnswer(i -> {
            StatusEntity value = i.getArgument(0);
            statusData.updateAndGet(all -> all.stream().filter(s -> !s.getId().equals(value.getId())).toList());
            return Mono.empty();
        });
    }

    @Test
    void statusPatchKeepsStableKeyAndReturnsFullVersionedGraphIncludingHiddenEdges() {
        StepVerifier.create(service.mutate(Operation.UPDATE_STATUS, command(4, 2L, null, "Проверка", 25, null, null, Map.of())))
                .assertNext(result -> {
                    assertThat(result.workflow().workflow().getVersion()).isEqualTo(5);
                    assertThat(result.issueTypes()).containsExactly(IssueType.TASK);
                    assertThat(result.workflow().statuses()).filteredOn(s -> s.getId().equals(id(2)))
                            .singleElement().satisfies(s -> {
                                assertThat(s.getStatusKey()).isEqualTo("REVIEW");
                                assertThat(s.getName()).isEqualTo("Проверка");
                                assertThat(s.getSortOrder()).isEqualTo(25);
                            });
                    assertThat(result.workflow().transitions()).anyMatch(TransitionEntity::isHidden);
                }).verifyComplete();
    }

    @Test
    void staleVersionCannotWriteOrMoveIssues() {
        StepVerifier.create(service.mutate(Operation.UPDATE_WORKFLOW, command(3, null, null, "Name", null, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.ABORTED)).verify();
        verify(workflows, never()).updateMetadata(any(), any(), anyInt());
        verifyNoInteractions(issues);
    }

    @Test
    void cannotDeleteTodoOrHideLastVisibleTransition() {
        StepVerifier.create(service.mutate(Operation.DELETE_STATUS, command(4, 1L, null, null, null, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.FAILED_PRECONDITION)).verify();
        transitionData.set(List.of(edge(13, 1, 3, 30, false)));
        StepVerifier.create(service.mutate(Operation.UPDATE_TRANSITION, command(4, null, 13L, null, null, true, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.INVALID_ARGUMENT)).verify();
        verify(transitions, never()).save(any());
    }

    @Test
    void changingToDuplicateSortOrderIsRejected() {
        StepVerifier.create(service.mutate(Operation.UPDATE_STATUS, command(4, 2L, null, null, 10, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.INVALID_ARGUMENT)).verify();
        verify(statuses, never()).save(any());
    }

    @Test
    void foreignNestedStatusReturns404() {
        StepVerifier.create(service.mutate(Operation.UPDATE_STATUS, command(4, 999L, null, "Foreign", null, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.NOT_FOUND)).verify();
        verify(statuses, never()).save(any());
    }

    @Test
    void usedStatusWithoutMappingKeepsEntireGraph() {
        when(issues.migrate(any(), any(), any(), any(), any())).thenReturn(Mono.error(mapper.GrpcExceptionMapper.toStatusRuntimeException(
                new DomainException(DomainStatus.FAILED_PRECONDITION, "Status has issues",
                        Map.of("reason", "WORKFLOW_ISSUES_UNMAPPED", "issueCount", "3", "statusKeys", "REVIEW")))));
        StepVerifier.create(service.mutate(Operation.DELETE_STATUS, command(4, 2L, null, null, null, null, null, Map.of())))
                .expectErrorSatisfies(e -> {
                    status(e, DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) e).getDetails()).containsExactlyInAnyOrderEntriesOf(Map.of(
                            "reason", "WORKFLOW_ISSUES_UNMAPPED", "issueCount", "3", "statusKeys", "REVIEW",
                            "targetStatusKeys", "TODO,DONE"));
                }).verify();
        assertThat(statusData.get()).hasSize(3);
        assertThat(transitionData.get()).hasSize(4);
    }

    @Test
    void movingIssuesPrecedesDeletingStatusAndItsIncidentEdges() {
        when(issues.migrate(any(), eq(List.of(IssueType.TASK)), eq(List.of("TODO", "DONE")), eq(Map.of("REVIEW", "DONE")), eq(List.of("REVIEW"))))
                .thenReturn(Mono.just(3L));
        StepVerifier.create(service.mutate(Operation.DELETE_STATUS, command(4, 2L, null, null, null, null, "DONE", Map.of())))
                .assertNext(result -> {
                    assertThat(result.workflow().statuses()).extracting(StatusEntity::getStatusKey).containsExactly("TODO", "DONE");
                    assertThat(result.workflow().transitions()).hasSize(2)
                            .noneMatch(t -> t.getFromStatusId().equals(id(2)) || t.getToStatusId().equals(id(2)));
                }).verifyComplete();
    }

    @Test
    void sharedDefaultAndWorkflowFromAnotherProjectCannotBeEdited() {
        when(bindings.findByWorkflowId(workflow)).thenReturn(Flux.just(binding(defaultProject)));
        StepVerifier.create(service.mutate(Operation.UPDATE_WORKFLOW, command(4, null, null, "Name", null, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.FAILED_PRECONDITION)).verify();
        when(bindings.findByWorkflowId(workflow)).thenReturn(Flux.just(binding(UUID.randomUUID())));
        StepVerifier.create(service.mutate(Operation.UPDATE_WORKFLOW, command(4, null, null, "Name", null, null, null, Map.of())))
                .expectErrorSatisfies(e -> status(e, DomainStatus.NOT_FOUND)).verify();
    }

    @Test
    void workflowDeletionDoesNotMoveIssuesToStatusAbsentFromSourceGraph() {
        UUID fallbackId = UUID.randomUUID();
        var fallback = entity.toBuilder().id(fallbackId).build();
        when(workflows.findWorkflowForProject(defaultProject, "TASK")).thenReturn(Mono.just(fallback));
        when(statuses.findActiveByWorkflowId(fallbackId)).thenReturn(Flux.just(status(1, "TODO", 10), status(99, "ARCHIVED", 20)));
        when(transitions.findByWorkflowIdOrderBySortOrderAsc(fallbackId)).thenReturn(Flux.just(edge(99, 1, 99, 10, false)));
        StepVerifier.create(service.mutate(Operation.DELETE_WORKFLOW,
                        command(4, null, null, null, null, null, null, Map.of("REVIEW", "ARCHIVED"))))
                .expectErrorSatisfies(e -> status(e, DomainStatus.INVALID_ARGUMENT)).verify();
        verifyNoInteractions(issues);
        verify(workflows, never()).delete(any(WorkflowEntity.class));
    }


    @Test
    void workflowDeletionOffersOnlyTargetsPresentInBothGraphs() {
        UUID fallbackId = UUID.randomUUID();
        when(workflows.findWorkflowForProject(defaultProject, "TASK")).thenReturn(Mono.just(entity.toBuilder().id(fallbackId).build()));
        when(statuses.findActiveByWorkflowId(fallbackId)).thenReturn(Flux.just(status(1, "TODO", 10), status(3, "DONE", 20), status(9, "ARCHIVED", 30)));
        when(transitions.findByWorkflowIdOrderBySortOrderAsc(fallbackId)).thenReturn(Flux.just(edge(90, 1, 3, 10, false)));
        when(issues.migrate(any(), any(), any(), any())).thenReturn(Mono.error(mapper.GrpcExceptionMapper.toStatusRuntimeException(
                new DomainException(DomainStatus.FAILED_PRECONDITION, "Status has issues",
                        Map.of("reason", "WORKFLOW_ISSUES_UNMAPPED", "issueCount", "2", "statusKeys", "REVIEW")))));
        StepVerifier.create(service.mutate(Operation.DELETE_WORKFLOW,
                        command(4, null, null, null, null, null, null, Map.of())))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getDetails())
                        .containsEntry("targetStatusKeys", "TODO,DONE")).verify();
        verify(workflows, never()).delete(any(WorkflowEntity.class));
        verify(bindings, never()).delete(any(WorkflowBindingEntity.class));
    }

    @Test
    void migrationLimitKeepsItsReasonAndDoesNotOfferMappingAsAResolution() {
        when(issues.migrate(any(), any(), any(), any(), any())).thenReturn(Mono.error(mapper.GrpcExceptionMapper.toStatusRuntimeException(
                new DomainException(DomainStatus.FAILED_PRECONDITION, "Migration exceeds 5000 issues",
                        Map.of("reason", "WORKFLOW_MIGRATION_LIMIT", "limit", "5000")))));
        StepVerifier.create(service.mutate(Operation.DELETE_STATUS, command(4, 2L, null, null, null, null, null, Map.of())))
                .expectErrorSatisfies(error -> {
                    status(error, DomainStatus.FAILED_PRECONDITION);
                    assertThat(((DomainException) error).getDetails()).containsExactlyInAnyOrderEntriesOf(
                            Map.of("reason", "WORKFLOW_MIGRATION_LIMIT", "limit", "5000"));
                }).verify();
        verify(statuses, never()).delete(any(StatusEntity.class));
    }

    private WorkflowMutation command(Integer version, Long status, Long transition, String name, Integer order,
                                       Boolean hidden, String target, Map<String, String> mapping) {
        return new WorkflowMutation("r", "n", actor, project, workflow, version, status == null ? null : id(status),
                transition == null ? null : id(transition), name, null, order, hidden, target, mapping);
    }

    private StatusEntity status(long value, String key, int order) {
        return StatusEntity.builder().id(id(value)).workflowId(workflow).statusKey(key).name(key)
                .category(StatusCategory.TODO).sortOrder(order).build();
    }

    private TransitionEntity edge(long value, long from, long to, int order, boolean hidden) {
        return TransitionEntity.builder().id(id(value)).workflowId(workflow).fromStatusId(id(from)).toStatusId(id(to))
                .name("Transition " + value).sortOrder(order).hidden(hidden).build();
    }

    private WorkflowBindingEntity binding(UUID project) {
        return WorkflowBindingEntity.builder().id(UUID.randomUUID()).projectId(project).workflowId(workflow).issueType("TASK").build();
    }

    private UUID id(long value) { return new UUID(0, value); }
    private void status(Throwable error, DomainStatus expected) {
        assertThat(error).isInstanceOf(DomainException.class);
        assertThat(((DomainException) error).getStatus()).isEqualTo(expected);
    }
}
