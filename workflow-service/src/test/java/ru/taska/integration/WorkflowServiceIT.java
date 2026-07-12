package ru.taska.integration;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.domain.StatusCategory;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.repository.WorkflowRepository;
import ru.taska.service.WorkflowService;

class WorkflowServiceIT extends AbstractIT{

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

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ISSUE_TYPE = "TASK";
    private static final String WRONG_ISSUE_TYPE = "ISSUE";
    private static final String DEFAULT_WORKFLOW_NAME = "Default workflow";
    private static final String TEST_WORKFLOW_NAME = "Test workflow";
    private static final UUID DEFAULT_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        cleanAll();
        seedAll();
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
    void shouldThrowInvalidArgument_causeWrongIssueType() {
        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, WRONG_ISSUE_TYPE))
                    .expectErrorSatisfies(error -> {
                        Assertions.assertInstanceOf(DomainException.class, error);
                        DomainException ex = (DomainException) error;
                        Assertions.assertEquals(DomainStatus.INVALID_ARGUMENT, ex.getStatus());
                    })
                    .verify();
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
