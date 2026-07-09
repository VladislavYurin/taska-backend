package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.WorkflowProperties;
import ru.taska.dto.TransitionViolation;
import ru.taska.dto.TransitionViolationDto;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.StatusRepository;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowRepository;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private TransitionRepository transitionRepository;

    @Mock
    private WorkflowProperties workflowProperties;

    @Mock
    private IssueMapper issueMapper;

    @Mock
    private WorkflowCreateService workflowCreateService;

    @InjectMocks
    private WorkflowServiceImpl workflowService;

    private UUID projectId;
    private UUID workflowId;
    private UUID transitionId;
    private UUID fromStatusId;
    private UUID toStatusId;
    private UUID actorUserId;
    private String issueType;
    private String currentStatusKey;
    private String requestId;
    private String nodeId;
    private String payload;
    private WorkflowEntity workflow;
    private TransitionEntity transition;
    private StatusEntity fromStatus;
    private StatusEntity toStatus;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        workflowId = UUID.randomUUID();
        transitionId = UUID.randomUUID();
        fromStatusId = UUID.randomUUID();
        toStatusId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        issueType = "TASK";
        currentStatusKey = "TODO";
        requestId = "test-request-123";
        nodeId = "test-node-456";
        payload = "{\"comment\": \"Starting work\"}";

        workflow = WorkflowEntity.builder()
                .id(workflowId)
                .name("Test Workflow")
                .build();

        transition = TransitionEntity.builder()
                .id(transitionId)
                .workflowId(workflowId)
                .fromStatusId(fromStatusId)
                .toStatusId(toStatusId)
                .name("Start Progress")
                .build();

        fromStatus = StatusEntity.builder()
                .id(fromStatusId)
                .workflowId(workflowId)
                .statusKey("TODO")
                .name("To Do")
                .category("TODO")
                .build();

        toStatus = StatusEntity.builder()
                .id(toStatusId)
                .workflowId(workflowId)
                .statusKey("IN_PROGRESS")
                .name("In Progress")
                .category("IN_PROGRESS")
                .build();
    }

    // ==================== УСПЕШНЫЙ ТЕСТ ====================
    @Test
    void validateTransition_Success() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.just(fromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.just(toStatus));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertTrue(response.isValid());
                    org.junit.jupiter.api.Assertions.assertEquals(
                            "IN_PROGRESS",
                            response.getToStatusKey()
                    );
                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertTrue(response.getViolations().isEmpty());
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

    // ==================== ПРОВАЛЬНЫЕ ТЕСТЫ ====================

    @Test
    void validateTransition_WorkflowNotFound_ShouldReturnViolation() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.empty());

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());
                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );
                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.WORKFLOW_NOT_FOUND,
                            violation.getTransitionViolation());

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("Workflow not found")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verifyNoInteractions(statusRepository);
        org.mockito.Mockito.verifyNoMoreInteractions(transitionRepository);
    }

    @Test
    void validateTransition_TransitionNotFound_ShouldReturnViolation() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.empty());

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());
                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey());
                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.TRANSITION_NOT_FOUND,
                            violation.getTransitionViolation());

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("Transition not found")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType)
        );

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verifyNoInteractions(statusRepository);
    }

    @Test
    void validateTransition_BothWorkflowAndTransitionNotFound_ShouldReturnBothViolations() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.empty());

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.empty());

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(2, response.getViolations().size());

                    TransitionViolationDto violation1 = response.getViolations().get(0);
                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.WORKFLOW_NOT_FOUND,
                            violation1.getTransitionViolation()
                    );

                    TransitionViolationDto violation2 = response.getViolations().get(1);
                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.TRANSITION_NOT_FOUND,
                            violation2.getTransitionViolation()
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verifyNoInteractions(statusRepository);
    }

    @Test
    void validateTransition_TransitionDoesNotBelongToWorkflow_ShouldReturnViolation() {
        // Arrange
        UUID differentWorkflowId = UUID.randomUUID();

        TransitionEntity transitionWithDifferentWorkflow = TransitionEntity.builder()
                .id(transitionId)
                .workflowId(differentWorkflowId)
                .fromStatusId(fromStatusId)
                .toStatusId(toStatusId)
                .name("Start Progress")
                .build();

        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transitionWithDifferentWorkflow));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.just(fromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.just(toStatus));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.TRANSITION_DOESNT_BELONG_TO_WORKFLOW,
                            violation.getTransitionViolation()
                    );

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("does not belong to workflow")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

    @Test
    void validateTransition_CurrentStatusNotFound_ShouldReturnViolation() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.empty());

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    System.out.println("=== RESPONSE ===");
                    System.out.println("valid: " + response.isValid());
                    System.out.println("toStatusKey: " + response.getToStatusKey());
                    System.out.println("violations: " + response.getViolations());
                    System.out.println("violations size: " + (response.getViolations() != null ? response.getViolations().size() : 0));
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.CURRENT_STATUS_NOT_FOUND,
                            violation.getTransitionViolation()
                    );

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("not found")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository, org.mockito.Mockito.never())
                .findById(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void validateTransition_MultipleViolations_ShouldReturnAllViolations() {
        // Arrange
        UUID differentWorkflowId = UUID.randomUUID();

        TransitionEntity transitionWithDifferentWorkflow = TransitionEntity.builder()
                .id(transitionId)
                .workflowId(differentWorkflowId)
                .fromStatusId(fromStatusId)
                .toStatusId(toStatusId)
                .name("Start Progress")
                .build();

        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transitionWithDifferentWorkflow));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.just(fromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.empty());

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertTrue(!response.getViolations().isEmpty());
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

    @Test
    void validateTransition_TargetStatusNotFound_ShouldReturnViolation() {
        // Arrange
        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.just(fromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.empty());

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.TARGET_STATUS_NOT_FOUND,
                            violation.getTransitionViolation()
                    );

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("Target status")
                    );

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("not found")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

    @Test
    void validateTransition_FromStatusDoesNotMatch_ShouldReturnViolation() {
        // Arrange
        UUID differentFromStatusId = UUID.randomUUID();

        TransitionEntity transitionWithDifferentFromStatus = TransitionEntity.builder()
                .id(transitionId)
                .workflowId(workflowId)
                .fromStatusId(differentFromStatusId) // different from fromStatus.getId()
                .toStatusId(toStatusId)
                .name("Start Progress")
                .build();

        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transitionWithDifferentFromStatus));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(currentStatusKey)))
                .thenReturn(Mono.just(fromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.just(toStatus));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertFalse(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            "ISSUE_STATUS_UNSPECIFIED",
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertEquals(1, response.getViolations().size());

                    TransitionViolationDto violation = response.getViolations().get(0);

                    org.junit.jupiter.api.Assertions.assertEquals(
                            TransitionViolation.FROM_STATUS_DOESNT_MATCH,
                            violation.getTransitionViolation()
                    );

                    org.junit.jupiter.api.Assertions.assertTrue(
                            violation.getMessage().contains("does not match")
                    );
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(currentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

    @Test
    void validateTransition_RepositoryThrowsException_ShouldPropagateError() {
        // Arrange
        RuntimeException dbException = new RuntimeException("Connection refused");

        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.error(dbException));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId, nodeId, projectId, issueType, transitionId,
                currentStatusKey, payload, actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorMatches(throwable ->
                        throwable instanceof RuntimeException &&
                                throwable.getMessage().equals("Connection refused")
                )
                .verify();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));
        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);
        org.mockito.Mockito.verifyNoInteractions(statusRepository);
    }

    @Test
    void validateTransition_CustomTargetStatus_ShouldReturnCustomStatusKey() {
        // Arrange
        String customCurrentStatusKey = "BACKLOG";
        String customTargetStatusKey = "QA_REVIEW";

        StatusEntity customFromStatus = StatusEntity.builder()
                .id(fromStatusId)
                .workflowId(workflowId)
                .statusKey(customCurrentStatusKey)
                .name("Backlog")
                .category("CUSTOM")
                .build();

        StatusEntity customToStatus = StatusEntity.builder()
                .id(toStatusId)
                .workflowId(workflowId)
                .statusKey(customTargetStatusKey)
                .name("QA Review")
                .category("CUSTOM")
                .build();

        org.mockito.Mockito.when(workflowRepository.findWorkflowForProject(
                        org.mockito.ArgumentMatchers.eq(projectId),
                        org.mockito.ArgumentMatchers.eq(issueType)))
                .thenReturn(Mono.just(workflow));

        org.mockito.Mockito.when(transitionRepository.findById(transitionId))
                .thenReturn(Mono.just(transition));

        org.mockito.Mockito.when(statusRepository.findByWorkflowIdAndStatusKey(
                        org.mockito.ArgumentMatchers.eq(workflowId),
                        org.mockito.ArgumentMatchers.eq(customCurrentStatusKey)))
                .thenReturn(Mono.just(customFromStatus));

        org.mockito.Mockito.when(statusRepository.findById(toStatusId))
                .thenReturn(Mono.just(customToStatus));

        // Act
        Mono<ValidateTransitionResponseDto> result = workflowService.validateTransition(
                requestId,
                nodeId,
                projectId,
                issueType,
                transitionId,
                customCurrentStatusKey,
                payload,
                actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(response);
                    org.junit.jupiter.api.Assertions.assertTrue(response.isValid());

                    org.junit.jupiter.api.Assertions.assertEquals(
                            customTargetStatusKey,
                            response.getToStatusKey()
                    );

                    org.junit.jupiter.api.Assertions.assertNotNull(response.getViolations());
                    org.junit.jupiter.api.Assertions.assertTrue(response.getViolations().isEmpty());
                })
                .verifyComplete();

        org.mockito.Mockito.verify(workflowRepository).findWorkflowForProject(
                org.mockito.ArgumentMatchers.eq(projectId),
                org.mockito.ArgumentMatchers.eq(issueType));

        org.mockito.Mockito.verify(transitionRepository).findById(transitionId);

        org.mockito.Mockito.verify(statusRepository).findByWorkflowIdAndStatusKey(
                org.mockito.ArgumentMatchers.eq(workflowId),
                org.mockito.ArgumentMatchers.eq(customCurrentStatusKey));

        org.mockito.Mockito.verify(statusRepository).findById(toStatusId);
    }

}
