package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.dto.WorkflowCreationViolation;
import ru.taska.dto.WorkflowCreationViolationType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;

class WorkflowCreationValidatorTest {

    private WorkflowCreationValidator validator;

    private WorkflowCreationDto validDto;

    @BeforeEach
    void setUp() {
        validator = new WorkflowCreationValidator(100, "^[A-Z][A-Z0-9_]{1,63}$");

        validDto = WorkflowCreationDto.builder()
                .projectId(UUID.randomUUID())
                .name("Test Workflow")
                .issueTypes(new ArrayList<>(List.of(IssueType.TASK, IssueType.BUG)))
                .statuses(new ArrayList<>(List.of(
                        CreateWorkflowStatusDto.builder()
                                .statusKey("BACKLOG")
                                .name("Backlog")
                                .category(StatusCategory.TODO)
                                .sortOrder(0)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("DEV")
                                .name("Development")
                                .category(StatusCategory.IN_PROGRESS)
                                .sortOrder(1)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("REVIEW")
                                .name("Review")
                                .category(StatusCategory.IN_PROGRESS)
                                .sortOrder(2)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("QA")
                                .name("Quality Assurance")
                                .category(StatusCategory.IN_PROGRESS)
                                .sortOrder(3)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("RELEASED")
                                .name("Released")
                                .category(StatusCategory.DONE)
                                .sortOrder(4)
                                .build()
                )))
                .transitions(new ArrayList<>(List.of(
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("BACKLOG")
                                .toStatusKey("DEV")
                                .name("Start Development")
                                .sortOrder(0)
                                .build(),
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("DEV")
                                .toStatusKey("REVIEW")
                                .name("Submit for Review")
                                .sortOrder(1)
                                .build(),
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("REVIEW")
                                .toStatusKey("QA")
                                .name("Send to QA")
                                .sortOrder(2)
                                .build(),
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("QA")
                                .toStatusKey("RELEASED")
                                .name("Release")
                                .sortOrder(3)
                                .build()
                )))
                .build();
    }

    @Test
    void validateDto_ValidDto_NoViolations() {
        List<WorkflowCreationViolation> violations = validator.validateDto(validDto);

        Assertions.assertTrue(violations.isEmpty());
    }

    @Test
    void validateDto_WorkflowNameIsBlank_ShouldReturnViolation() {
        validDto.setName("   ");

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.BLANK_NAME);
    }

    @Test
    void validateDto_StatusNameTooLong_ShouldReturnViolation() {
        validDto.getStatuses().get(0).setName("A".repeat(101));

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.TOO_LONG_NAME);
    }

    @Test
    void validateDto_TransitionNameIsNull_ShouldReturnViolation() {
        validDto.getTransitions().get(0).setName(null);

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.BLANK_NAME);
    }

    @Test
    void validateDto_StatusKeyInvalidFormat_ShouldReturnViolation() {
        validDto.getStatuses().get(0).setStatusKey("invalid-key");

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.INVALID_STATUS_KEY_FORMAT);
    }

    @Test
    void validateDto_IssueTypeListEmpty_ShouldReturnViolation() {
        validDto.setIssueTypes(List.of());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.ISSUE_TYPE_LIST_EMPTY);
    }

    @Test
    void validateDto_IssueTypeListNull_ShouldReturnViolation() {
        validDto.setIssueTypes(null);

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.ISSUE_TYPE_LIST_EMPTY);
    }

    @Test
    void validateDto_DuplicateIssueType_ShouldReturnViolation() {
        validDto.setIssueTypes(List.of(IssueType.TASK, IssueType.TASK));

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.DUPLICATE_ISSUE_TYPE);
    }

    @Test
    void validateDto_DuplicateStatusKey_ShouldReturnViolation() {
        validDto.getStatuses().add(CreateWorkflowStatusDto.builder()
                .statusKey("BACKLOG")
                .name("Backlog Copy")
                .category(StatusCategory.TODO)
                .sortOrder(10)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.DUPLICATE_STATUS_KEY);
    }

    @Test
    void validateDto_DuplicateStatusSortOrder_ShouldReturnViolation() {
        validDto.getStatuses().add(CreateWorkflowStatusDto.builder()
                .statusKey("ON_HOLD")
                .name("On Hold")
                .category(StatusCategory.IN_PROGRESS)
                .sortOrder(1)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.DUPLICATE_STATUS_SORT_ORDER);
    }

    @Test
    void validateDto_NullStatusCategory_ShouldReturnViolation() {
        validDto.getStatuses().get(1).setCategory(null);

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.INVALID_STATUS_CATEGORY);
    }

    @Test
    void validateDto_TransitionFromUnknownStatus_ShouldReturnViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("UNKNOWN")
                .toStatusKey("RELEASED")
                .name("Name")
                .sortOrder(10)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.TRANSITION_REFERENCES_UNKNOWN_STATUS);
    }

    @Test
    void validateDto_TransitionToUnknownStatus_ShouldReturnViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("BACKLOG")
                .toStatusKey("UNKNOWN")
                .name("Name")
                .sortOrder(10)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.TRANSITION_REFERENCES_UNKNOWN_STATUS);
    }

    @Test
    void validateDto_MultipleTransitionsFromSameUnknownStatus_ShouldReturnOneViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("UNKNOWN")
                .toStatusKey("BACKLOG")
                .name("Name")
                .sortOrder(10)
                .build());
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("UNKNOWN")
                .toStatusKey("RELEASED")
                .name("Name")
                .sortOrder(11)
                .build());

        Assertions.assertEquals(1, countViolations(validator.validateDto(validDto), WorkflowCreationViolationType.TRANSITION_REFERENCES_UNKNOWN_STATUS));
    }

    @Test
    void validateDto_SelfTransition_ShouldReturnViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("BACKLOG")
                .toStatusKey("BACKLOG")
                .name("Stay")
                .sortOrder(10)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.SELF_TRANSITION);
    }

    @Test
    void validateDto_DuplicateTransition_ShouldReturnViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("BACKLOG")
                .toStatusKey("DEV")
                .name("Start Development")
                .sortOrder(10)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.DUPLICATE_TRANSITION);
    }

    @Test
    void validateDto_DuplicateTransitionSortOrder_ShouldReturnViolation() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("RELEASED")
                .toStatusKey("BACKLOG")
                .name("Reopen")
                .sortOrder(0)
                .build());

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.DUPLICATE_TRANSITION_SORT_ORDER);
    }

    @Test
    void validateDto_StatusWithNullStatusKey_ShouldReturnViolations() {
        validDto.getStatuses().add(CreateWorkflowStatusDto.builder()
                .statusKey(null)
                .name("Review")
                .category(StatusCategory.IN_PROGRESS)
                .sortOrder(10)
                .build());

        Assertions.assertFalse(validator.validateDto(validDto).isEmpty());
    }

    @Test
    void validateDto_StatusWithNullCategory_ShouldReturnViolations() {
        validDto.getStatuses().add(CreateWorkflowStatusDto.builder()
                .statusKey("ON_HOLD")
                .name("On Hold")
                .category(null)
                .sortOrder(10)
                .build());

        Assertions.assertFalse(validator.validateDto(validDto).isEmpty());
    }

    @Test
    void validateDto_TransitionWithNullFromStatusKey_ShouldReturnViolations() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey(null)
                .toStatusKey("RELEASED")
                .name("Name")
                .sortOrder(10)
                .build());

        Assertions.assertFalse(validator.validateDto(validDto).isEmpty());
    }

    @Test
    void validateDto_TransitionWithNullToStatusKey_ShouldReturnViolations() {
        validDto.getTransitions().add(CreateWorkflowTransitionDto.builder()
                .fromStatusKey("BACKLOG")
                .toStatusKey(null)
                .name("Name")
                .sortOrder(10)
                .build());

        Assertions.assertFalse(validator.validateDto(validDto).isEmpty());
    }

    @Test
    void validateDto_StatusWithNegativeSortOrder_ShouldReturnViolation() {
        validDto.getStatuses().get(0).setSortOrder(-1);

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.NEGATIVE_SORT_ORDER);
    }

    @Test
    void validateDto_TransitionWithNegativeSortOrder_ShouldReturnViolation() {
        validDto.getTransitions().get(0).setSortOrder(-1);

        assertSingleViolation(validator.validateDto(validDto), WorkflowCreationViolationType.NEGATIVE_SORT_ORDER);
    }

    private void assertSingleViolation(List<WorkflowCreationViolation> violations,
                                       WorkflowCreationViolationType expectedType) {
        Assertions.assertEquals(1, countViolations(violations, expectedType),
                "Expected exactly one violation of type " + expectedType + ", but got: " + violations);
    }

    private long countViolations(List<WorkflowCreationViolation> violations,
                                 WorkflowCreationViolationType type) {
        return violations.stream()
                .filter(v -> v.type() == type)
                .count();
    }
}
