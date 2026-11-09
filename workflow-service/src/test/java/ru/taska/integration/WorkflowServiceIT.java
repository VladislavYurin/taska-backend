package ru.taska.integration;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.RegistryFixtures;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.service.IssueTypeBindingIdentity;
import ru.taska.service.WorkflowService;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

class WorkflowServiceIT extends LegacyWorkflowOperationsFixture {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowMapper workflowMapper;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowBindingRepository workflowBindingRepository;

    @Autowired
    private StatusRepository statusRepository;

    @Autowired
    private TransitionRepository transitionRepository;

    @MockitoBean
    private GrpcProjectServiceClient projectClient;

    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ISSUE_TYPE = "TASK";
    private static final String CUSTOM_ISSUE_TYPE = "ISSUE";
    private static final String DEFAULT_WORKFLOW_NAME = "Default workflow";
    private static final String TEST_WORKFLOW_NAME = "Test workflow";
    private static final UUID DEFAULT_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        cleanAll();
        seedAll();
    }

    @BeforeEach
    void stubProjectRegistry() {
        Mockito.when(projectClient.getIssueTypesInternal(Mockito.any()))
                .thenAnswer(invocation -> Mono.just(RegistryFixtures.types(
                        invocation.getArgument(0),
                        IssueType.TASK, IssueType.BUG, IssueType.STORY,
                        IssueType.valueOf(CUSTOM_ISSUE_TYPE))));
    }

    @Test
    void shouldReturnDefaultWorkflow() {
        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, ISSUE_TYPE))
                .assertNext(result -> {
                    Assertions.assertEquals(result.workflow().getName(), DEFAULT_WORKFLOW_NAME);
                })
                .verifyComplete();
    }

    @Test
    void customIssueTypeUsesCommonDefaultWorkflow() {
        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, CUSTOM_ISSUE_TYPE))
                .assertNext(result -> Assertions.assertEquals(DEFAULT_WORKFLOW_NAME, result.workflow().getName()))
                .verifyComplete();
    }

    @Test
    void shouldThrowNotFound_causeDefaultWorkflowDoesntExist() {
        cleanAll();
        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, ISSUE_TYPE))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertInstanceOf(DomainException.class, error);
                        DomainException ex = (DomainException) error;
                        Assertions.assertEquals(DomainStatus.NOT_FOUND, ex.getStatus());
                    })
                    .verify();
    }

    @Test
    void shouldReturnWorkflowForProject() {
        WorkflowEntity workflow = workflowRepository.save(WorkflowEntity.builder()
                                                                        .name(TEST_WORKFLOW_NAME)
                                                                        .version(1)
                                                                        .active(true)
                                                                        .build())
                                                    .block();

        createWorkflowBinding(workflow, PROJECT_ID);

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, ISSUE_TYPE))
                    .assertNext(result -> {
                        Assertions.assertEquals(result.workflow().getName(), TEST_WORKFLOW_NAME);
                    })
                    .verifyComplete();
    }

    @Test
    void incrementVersion_WithStaleVersion_ReturnsZeroAndKeepsVersion() {
        WorkflowEntity workflow = workflowRepository.save(WorkflowEntity.builder()
                        .name("Versioned workflow")
                        .version(1)
                        .active(true)
                        .build())
                .block();

        StepVerifier.create(workflowRepository.incrementVersion(workflow.getId(), 1))
                .expectNext(1)
                .verifyComplete();

        StepVerifier.create(workflowRepository.incrementVersion(workflow.getId(), 1))
                .expectNext(0)
                .verifyComplete();

        Assertions.assertEquals(2, workflowRepository.findById(workflow.getId()).block().getVersion());
    }

    @Test
    void boundWorkflowWithoutStatuses_returnsEmptyStatusesAndTransitions() {
        WorkflowEntity workflow = saveWorkflow();
        saveBinding(workflow.getId(), CUSTOM_ISSUE_TYPE);

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, CUSTOM_ISSUE_TYPE))
                .assertNext(result -> {
                    assertResolved(workflow, result);
                    Assertions.assertAll(
                            () -> Assertions.assertTrue(result.statuses().isEmpty()),
                            () -> Assertions.assertTrue(result.transitions().isEmpty()));
                })
                .verifyComplete();
    }

    @Test
    void boundWorkflowWithOnlyHiddenTransitions_returnsStatusesAndEmptyTransitions() {
        WorkflowEntity workflow = saveWorkflow();
        StatusEntity todo = saveStatus(workflow.getId(), "TODO", StatusCategory.TODO, 10);
        StatusEntity inProgress = saveStatus(workflow.getId(), "IN_PROGRESS", StatusCategory.IN_PROGRESS, 20);
        saveTransition(workflow.getId(), todo.getId(), inProgress.getId(), "Start Progress", true, 10);
        saveBinding(workflow.getId(), CUSTOM_ISSUE_TYPE);

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, CUSTOM_ISSUE_TYPE))
                .assertNext(result -> {
                    assertResolved(workflow, result);
                    Assertions.assertAll(
                            () -> Assertions.assertEquals(List.of("TODO", "IN_PROGRESS"), statusKeys(result)),
                            () -> Assertions.assertTrue(result.transitions().isEmpty()));
                })
                .verifyComplete();
    }

    private WorkflowEntity saveWorkflow() {
        return workflowRepository.save(WorkflowEntity.builder()
                        .name(TEST_WORKFLOW_NAME)
                        .version(1)
                        .active(true)
                        .build())
                .block();
    }

    private WorkflowBindingEntity saveBinding(UUID workflowId, String issueType) {
        return workflowBindingRepository.save(WorkflowBindingEntity.builder()
                        .workflowId(workflowId)
                        .projectId(PROJECT_ID)
                        .issueType(issueType)
                        .issueTypeGeneration(IssueTypeBindingIdentity.initialGeneration(PROJECT_ID, issueType))
                        .build())
                .block();
    }

    private StatusEntity saveStatus(UUID workflowId, String statusKey, StatusCategory category, int sortOrder) {
        return statusRepository.save(StatusEntity.builder()
                        .workflowId(workflowId)
                        .statusKey(statusKey)
                        .name(statusKey)
                        .category(category)
                        .sortOrder(sortOrder)
                        .build())
                .block();
    }

    private TransitionEntity saveTransition(UUID workflowId, UUID fromStatusId, UUID toStatusId, String name,
                                boolean hidden, int sortOrder) {
        return transitionRepository.save(TransitionEntity.builder()
                        .workflowId(workflowId)
                        .fromStatusId(fromStatusId)
                        .toStatusId(toStatusId)
                        .name(name)
                        .hidden(hidden)
                        .sortOrder(sortOrder)
                        .build())
                .block();
    }

    private static List<String> statusKeys(WorkflowAggregate aggregate) {
        return aggregate.statuses().stream()
                .map(StatusEntity::getStatusKey)
                .toList();
    }

    private static void assertResolved(WorkflowEntity expected, WorkflowAggregate actual) {
        Assertions.assertAll(
                () -> Assertions.assertEquals(expected.getId(), actual.workflow().getId()),
                () -> Assertions.assertEquals(expected.getName(), actual.workflow().getName()));
    }

     private void cleanAll() {
        workflowBindingRepository.deleteAll()
                                 .then(transitionRepository.deleteAll())
                                 .then(statusRepository.deleteAll())
                                 .then(workflowRepository.deleteAll())
                                 .block();
    }

    private void seedAll() {
        WorkflowEntity defaultWorkflow = workflowRepository.save(WorkflowEntity.builder()
                                                                        .name("Default workflow")
                                                                        .version(1)
                                                                        .active(true)
                                                                        .build()).block();

        StatusEntity todoStatus = statusRepository.save(StatusEntity.builder()
                                                                    .workflowId(defaultWorkflow.getId())
                                                                    .statusKey("TODO")
                                                                    .name("To Do")
                                                                    .category(StatusCategory.TODO)
                                                                    .sortOrder(10)
                                                                    .build()).block();
        StatusEntity inProgressStatus = statusRepository.save(StatusEntity.builder()
                                                                          .workflowId(defaultWorkflow.getId())
                                                                          .statusKey("IN_PROGRESS")
                                                                          .name("In Progress")
                                                                          .category(StatusCategory.IN_PROGRESS)
                                                                          .sortOrder(20)
                                                                          .build()).block();
        StatusEntity doneStatus = statusRepository.save(StatusEntity.builder()
                                                                    .workflowId(defaultWorkflow.getId())
                                                                    .statusKey("DONE")
                                                                    .name("Done")
                                                                    .category(StatusCategory.DONE)
                                                                    .sortOrder(30)
                                                                    .build()).block();
        TransitionEntity transitionStart = transitionRepository.save(TransitionEntity.builder()
                                                                                     .workflowId(defaultWorkflow.getId())
                                                                                     .fromStatusId(todoStatus.getId())
                                                                                     .toStatusId(inProgressStatus.getId())
                                                                                     .name("Start Progress")
                                                                                     .hidden(false)
                                                                                     .sortOrder(10)
                                                                                     .build()).block();
        TransitionEntity transitionComplete = transitionRepository.save(TransitionEntity.builder()
                                                                                        .workflowId(defaultWorkflow.getId())
                                                                                        .fromStatusId(inProgressStatus.getId())
                                                                                        .toStatusId(doneStatus.getId())
                                                                                        .name("Complete")
                                                                                        .hidden(false)
                                                                                        .sortOrder(20)
                                                                                        .build()).block();
        TransitionEntity transitionReopen = transitionRepository.save(TransitionEntity.builder()
                                                                                      .workflowId(defaultWorkflow.getId())
                                                                                      .fromStatusId(doneStatus.getId())
                                                                                      .toStatusId(inProgressStatus.getId())
                                                                                      .name("Reopen")
                                                                                      .hidden(false)
                                                                                      .sortOrder(30)
                                                                                      .build()).block();
        workflowBindingRepository.saveAll(List.of(
                WorkflowBindingEntity.builder().projectId(DEFAULT_PROJECT_ID)
                                     .issueType("TASK").workflowId(defaultWorkflow.getId()).build(),
                WorkflowBindingEntity.builder().projectId(DEFAULT_PROJECT_ID)
                                     .issueType("BUG").workflowId(defaultWorkflow.getId()).build(),
                WorkflowBindingEntity.builder().projectId(DEFAULT_PROJECT_ID)
                                     .issueType("STORY").workflowId(defaultWorkflow.getId()).build()
        )).blockLast();
    }

    private void createWorkflowBinding(WorkflowEntity workflow, UUID projectId) {

        StatusEntity todoStatus = statusRepository.save(StatusEntity.builder()
                                                                    .workflowId(workflow.getId())
                                                                    .statusKey("TODO")
                                                                    .name("To Do")
                                                                    .category(StatusCategory.TODO)
                                                                    .sortOrder(10)
                                                                    .build()).block();
        StatusEntity inProgressStatus = statusRepository.save(StatusEntity.builder()
                                                                          .workflowId(workflow.getId())
                                                                          .statusKey("IN_PROGRESS")
                                                                          .name("In Progress")
                                                                          .category(StatusCategory.IN_PROGRESS)
                                                                          .sortOrder(20)
                                                                          .build()).block();
        StatusEntity doneStatus = statusRepository.save(StatusEntity.builder()
                                                                    .workflowId(workflow.getId())
                                                                    .statusKey("DONE")
                                                                    .name("Done")
                                                                    .category(StatusCategory.DONE)
                                                                    .sortOrder(30)
                                                                    .build()).block();
        TransitionEntity transitionStart = transitionRepository.save(TransitionEntity.builder()
                                                                                     .workflowId(
                                                                                             workflow.getId())
                                                                                     .fromStatusId(todoStatus.getId())
                                                                                     .toStatusId(inProgressStatus.getId())
                                                                                     .name("Start Progress")
                                                                                     .hidden(false)
                                                                                     .sortOrder(10)
                                                                                     .build()).block();
        TransitionEntity transitionComplete = transitionRepository.save(TransitionEntity.builder()
                                                                                        .workflowId(
                                                                                                workflow.getId())
                                                                                        .fromStatusId(inProgressStatus.getId())
                                                                                        .toStatusId(doneStatus.getId())
                                                                                        .name("Complete")
                                                                                        .hidden(false)
                                                                                        .sortOrder(20)
                                                                                        .build()).block();
        TransitionEntity transitionReopen = transitionRepository.save(TransitionEntity.builder()
                                                                                      .workflowId(
                                                                                              workflow.getId())
                                                                                      .fromStatusId(doneStatus.getId())
                                                                                      .toStatusId(inProgressStatus.getId())
                                                                                      .name("Reopen")
                                                                                      .hidden(false)
                                                                                      .sortOrder(30)
                                                                                      .build()).block();
        workflowBindingRepository.saveAll(List.of(
                WorkflowBindingEntity.builder().projectId(projectId)
                                     .issueType("TASK").workflowId(workflow.getId()).build(),
                WorkflowBindingEntity.builder().projectId(projectId)
                                     .issueType("BUG").workflowId(workflow.getId()).build(),
                WorkflowBindingEntity.builder().projectId(projectId)
                                     .issueType("STORY").workflowId(workflow.getId()).build()
        )).blockLast();
    }
}
