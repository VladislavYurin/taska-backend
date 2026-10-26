package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowEditServiceTest {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "some-service";
    private static final UUID DEFAULT_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID TODO_ID = UUID.randomUUID();
    private static final UUID IN_PROGRESS_ID = UUID.randomUUID();
    private static final UUID DONE_ID = UUID.randomUUID();

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private TransitionRepository transitionRepository;

    @Mock
    private WorkflowBindingRepository bindingRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;

    private WorkflowEditService service;

    @BeforeEach
    void setUp() {
        WorkflowProperties properties = new WorkflowProperties(DEFAULT_PROJECT_ID,
                new WorkflowProperties.AllowedRoles(Set.of(ProjectRole.ADMIN), Set.of(ProjectRole.ADMIN)));
        service = new WorkflowEditService(
                new WorkflowCreationValidator(100, "^[A-Z][A-Z0-9_]{1,63}$"),
                new WorkflowCreationMapper(),
                workflowRepository,
                statusRepository,
                transitionRepository,
                bindingRepository,
                projectRoleChecker,
                properties, projectClient);

        lenient().when(projectClient.listIssueTypeDetails(any(), any(), any(), any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(PROJECT_ID, ru.taska.domain.IssueType.values())));
        WorkflowEntity workflow = WorkflowEntity.builder()
                .id(WORKFLOW_ID)
                .name("Project workflow")
                .version(1)
                .active(true)
                .build();

        lenient().when(projectRoleChecker.checkProjectWriteRole(anyString(), anyString(), any(UUID.class), any(UUID.class), anySet()))
                .thenReturn(Mono.empty());
        // Первое чтение — перед изменением, второе — после атомарного увеличения версии.
        lenient().when(workflowRepository.findByIdForUpdate(WORKFLOW_ID)).thenReturn(Mono.just(workflow));
        lenient().when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Mono.just(workflow.toBuilder().version(2).build()));
        lenient().when(workflowRepository.incrementVersion(WORKFLOW_ID)).thenReturn(Mono.just(1));
        lenient().when(bindingRepository.findByWorkflowId(WORKFLOW_ID))
                .thenReturn(Flux.just(binding(PROJECT_ID, "TASK")));
        lenient().when(statusRepository.findActiveByWorkflowId(WORKFLOW_ID)).thenReturn(Flux.just(
                status(TODO_ID, "TODO", StatusCategory.TODO, 10),
                status(IN_PROGRESS_ID, "IN_PROGRESS", StatusCategory.IN_PROGRESS, 20),
                status(DONE_ID, "DONE", StatusCategory.DONE, 30)));
        List<TransitionEntity> transitions = List.of(
                transition(TODO_ID, IN_PROGRESS_ID, "Start Progress", 10),
                transition(IN_PROGRESS_ID, DONE_ID, "Complete", 20));
        lenient().when(transitionRepository.findByWorkflowIdOrderBySortOrderAsc(WORKFLOW_ID))
                .thenReturn(Flux.fromIterable(transitions));
        lenient().when(transitionRepository.findVisibleByWorkflowId(WORKFLOW_ID))
                .thenReturn(Flux.fromIterable(transitions));
        lenient().when(statusRepository.save(any(StatusEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(transitionRepository.save(any(TransitionEntity.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void editableTypesFollowRegistryOrderAndExcludeDeletedCodes() {
        when(projectClient.listIssueTypeDetails(any(), any(), any(), any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(PROJECT_ID,
                        ru.taska.domain.IssueType.BUG, ru.taska.domain.IssueType.TASK)));
        when(bindingRepository.findByWorkflowId(WORKFLOW_ID))
                .thenReturn(Flux.just(binding(PROJECT_ID, "TASK"), binding(PROJECT_ID, "REMOVED"), binding(PROJECT_ID, "BUG")));
        StepVerifier.create(service.loadEditableWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID, true))
                .assertNext(current -> Assertions.assertEquals(List.of(ru.taska.domain.IssueType.BUG, ru.taska.domain.IssueType.TASK), current.issueTypes()))
                .verifyComplete();
    }

    @Test
    void addStatus_ValidStatus_ShouldSaveStatusAndBumpVersion() {
        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .assertNext(aggregate -> Assertions.assertEquals(2, aggregate.workflow().getVersion()))
                .verifyComplete();

        verify(workflowRepository).incrementVersion(WORKFLOW_ID);
        ArgumentCaptor<StatusEntity> saved = ArgumentCaptor.forClass(StatusEntity.class);
        verify(statusRepository).save(saved.capture());
        Assertions.assertEquals(WORKFLOW_ID, saved.getValue().getWorkflowId());
        Assertions.assertEquals("CODE_REVIEW", saved.getValue().getStatusKey());
        Assertions.assertEquals(StatusCategory.IN_PROGRESS, saved.getValue().getCategory());
        Assertions.assertEquals(25, saved.getValue().getSortOrder());
        verify(projectRoleChecker).checkProjectWriteRole(eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(ACTOR_USER_ID),
                eq(Set.of(ProjectRole.ADMIN)));
    }

    @Test
    void addStatus_ExistingStatusKey_ShouldReturnAlreadyExists() {
        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("DONE", 40)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.ALREADY_EXISTS))
                .verify();

        verify(statusRepository, never()).save(any());
        verify(workflowRepository, never()).incrementVersion(any());
    }

    @Test
    void addStatus_ExistingSortOrder_ShouldReturnInvalidArgument() {
        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 20)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        verify(statusRepository, never()).save(any());
    }

    @Test
    void addStatus_InvalidStatusKeyFormat_ShouldReturnInvalidArgument() {
        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("code review", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        verify(statusRepository, never()).save(any());
    }

    @Test
    void addStatus_DefaultWorkflow_ShouldReturnFailedPrecondition() {
        when(bindingRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Flux.just(
                binding(DEFAULT_PROJECT_ID, "TASK"),
                binding(DEFAULT_PROJECT_ID, "BUG")));

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.FAILED_PRECONDITION))
                .verify();

        verify(statusRepository, never()).save(any());
    }

    @Test
    void addStatus_WorkflowOfAnotherProject_ShouldReturnNotFound() {
        when(bindingRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Flux.just(binding(UUID.randomUUID(), "TASK")));

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.NOT_FOUND))
                .verify();

        verify(statusRepository, never()).save(any());
    }

    @Test
    void addStatus_UnknownWorkflow_ShouldReturnNotFound() {
        UUID unknownWorkflowId = UUID.randomUUID();
        when(workflowRepository.findByIdForUpdate(unknownWorkflowId)).thenReturn(Mono.empty());

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, unknownWorkflowId,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void addStatus_InactiveWorkflow_ShouldReturnNotFound() {
        when(workflowRepository.findByIdForUpdate(WORKFLOW_ID)).thenReturn(Mono.just(WorkflowEntity.builder()
                .id(WORKFLOW_ID)
                .name("Project workflow")
                .version(1)
                .active(false)
                .build()));

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.NOT_FOUND))
                .verify();

        verify(statusRepository, never()).save(any());
    }

    @Test
    void addStatus_RoleNotAllowed_ShouldNotReadWorkflow() {
        when(projectRoleChecker.checkProjectWriteRole(anyString(), anyString(), any(UUID.class), any(UUID.class), anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Not allowed role")));

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.PERMISSION_DENIED))
                .verify();

        verifyNoInteractions(workflowRepository, statusRepository, transitionRepository, bindingRepository);
    }

    @Test
    void addStatus_ConcurrentDuplicateKey_ShouldReturnAlreadyExists() {
        when(statusRepository.save(any(StatusEntity.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("statuses_workflow_status_uniq")));

        StepVerifier.create(service.addStatus(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        statusDto("CODE_REVIEW", 25)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.ALREADY_EXISTS))
                .verify();
    }

    @Test
    void addTransition_ValidTransition_ShouldSaveWithStatusIdsAndBumpVersion() {
        StepVerifier.create(service.addTransition(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        transitionDto("IN_PROGRESS", "TODO", 30)))
                .assertNext(aggregate -> Assertions.assertEquals(2, aggregate.workflow().getVersion()))
                .verifyComplete();

        ArgumentCaptor<TransitionEntity> saved = ArgumentCaptor.forClass(TransitionEntity.class);
        verify(transitionRepository).save(saved.capture());
        Assertions.assertEquals(WORKFLOW_ID, saved.getValue().getWorkflowId());
        Assertions.assertEquals(IN_PROGRESS_ID, saved.getValue().getFromStatusId());
        Assertions.assertEquals(TODO_ID, saved.getValue().getToStatusId());
        Assertions.assertEquals(30, saved.getValue().getSortOrder());
    }

    @Test
    void addTransition_ExistingPair_ShouldReturnAlreadyExists() {
        StepVerifier.create(service.addTransition(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        transitionDto("TODO", "IN_PROGRESS", 30)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.ALREADY_EXISTS))
                .verify();

        verify(transitionRepository, never()).save(any());
    }

    @Test
    void addTransition_UnknownStatus_ShouldReturnInvalidArgument() {
        StepVerifier.create(service.addTransition(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        transitionDto("IN_PROGRESS", "QA", 30)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        verify(transitionRepository, never()).save(any());
    }

    @Test
    void addTransition_SelfTransition_ShouldReturnInvalidArgument() {
        StepVerifier.create(service.addTransition(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        transitionDto("DONE", "DONE", 30)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.INVALID_ARGUMENT))
                .verify();

        verify(transitionRepository, never()).save(any());
    }

    @Test
    void addTransition_DefaultWorkflow_ShouldReturnFailedPrecondition() {
        when(bindingRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Flux.just(binding(DEFAULT_PROJECT_ID, "TASK")));

        StepVerifier.create(service.addTransition(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID, WORKFLOW_ID,
                        transitionDto("IN_PROGRESS", "TODO", 30)))
                .expectErrorSatisfies(error -> assertDomainStatus(error, DomainStatus.FAILED_PRECONDITION))
                .verify();

        verify(transitionRepository, never()).save(any());
    }

    private static void assertDomainStatus(Throwable error, DomainStatus expected) {
        Assertions.assertInstanceOf(DomainException.class, error);
        Assertions.assertEquals(expected, ((DomainException) error).getStatus());
    }

    private static CreateWorkflowStatusDto statusDto(String key, int sortOrder) {
        return CreateWorkflowStatusDto.builder()
                .statusKey(key)
                .name("Status " + key)
                .category(StatusCategory.IN_PROGRESS)
                .sortOrder(sortOrder)
                .build();
    }

    private static CreateWorkflowTransitionDto transitionDto(String from, String to, int sortOrder) {
        return CreateWorkflowTransitionDto.builder()
                .fromStatusKey(from)
                .toStatusKey(to)
                .name(from + " to " + to)
                .sortOrder(sortOrder)
                .build();
    }

    private static WorkflowBindingEntity binding(UUID projectId, String issueType) {
        return WorkflowBindingEntity.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .issueType(issueType)
                .workflowId(WORKFLOW_ID)
                .build();
    }

    private static StatusEntity status(UUID id, String key, StatusCategory category, int sortOrder) {
        return StatusEntity.builder()
                .id(id)
                .workflowId(WORKFLOW_ID)
                .statusKey(key)
                .name(key)
                .category(category)
                .sortOrder(sortOrder)
                .build();
    }

    private static TransitionEntity transition(UUID from, UUID to, String name, int sortOrder) {
        return TransitionEntity.builder()
                .id(UUID.randomUUID())
                .workflowId(WORKFLOW_ID)
                .fromStatusId(from)
                .toStatusId(to)
                .name(name)
                .sortOrder(sortOrder)
                .build();
    }
}
