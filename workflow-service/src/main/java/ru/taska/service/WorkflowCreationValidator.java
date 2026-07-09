package ru.taska.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.taska.domain.IssueType;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.dto.WorkflowCreationViolation;
import ru.taska.dto.WorkflowCreationViolationType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Component
public class WorkflowCreationValidator {

    private final int nameMaxLength;
    private final Pattern statusKeyPattern;

    public WorkflowCreationValidator(
            @Value("${workflow.validation.name-max-length}") int nameMaxLength,
            @Value("${workflow.validation.status-key-pattern}") String statusKeyPattern) {
        this.nameMaxLength = nameMaxLength;
        this.statusKeyPattern = Pattern.compile(statusKeyPattern);
    }

    public List<WorkflowCreationViolation> validateDto(WorkflowCreationDto dto) {
        return Stream.of(
                validateNames(dto),
                validateNameLengths(dto),
                validateIssueTypes(dto),
                validateStatusKeys(dto),
                validateStatusAndSortOrderUniqueness(dto),
                validateStatusCategories(dto),
                validateTransitionStatusReferences(dto),
                validateNoSelfTransitions(dto),
                validateNoDuplicateTransitions(dto),
                validateSortOrders(dto)
        ).flatMap(List::stream).toList();
    }

    public String buildViolationMessage(List<WorkflowCreationViolation> violations) {
        return violations.stream()
                .map(WorkflowCreationViolation::message)
                .collect(Collectors.joining(", ", "Workflow creation failed: ", ""));
    }

    // Имена workflow, статусов и переходов не пустые.
    private List<WorkflowCreationViolation> validateNames(WorkflowCreationDto dto) {
        List<WorkflowCreationViolation> violations = new ArrayList<>();
        if (dto.getName() == null || dto.getName().isBlank()) {
            violations.add(violation(WorkflowCreationViolationType.BLANK_NAME, "Workflow name must not be blank"));
        }
        for (CreateWorkflowStatusDto status : dto.getStatuses()) {
            if (status.getName() == null || status.getName().isBlank()) {
                violations.add(violation(WorkflowCreationViolationType.BLANK_NAME,
                        "Status name must not be blank for status: " + status.getStatusKey()));
            }
        }
        for (CreateWorkflowTransitionDto transition : dto.getTransitions()) {
            if (transition.getName() == null || transition.getName().isBlank()) {
                violations.add(violation(WorkflowCreationViolationType.BLANK_NAME,
                        "Transition name must not be blank: from " + transition.getFromStatusKey() + " to " + transition.getToStatusKey()));
            }
        }
        return violations;
    }

    // Имена workflow, статусов и переходов не превышают максимальную длину.
    private List<WorkflowCreationViolation> validateNameLengths(WorkflowCreationDto dto) {
        List<WorkflowCreationViolation> violations = new ArrayList<>();
        if (dto.getName() != null && dto.getName().length() > nameMaxLength) {
            violations.add(violation(WorkflowCreationViolationType.TOO_LONG_NAME,
                    "Workflow name exceeds maximum length of " + nameMaxLength));
        }
        for (CreateWorkflowStatusDto status : dto.getStatuses()) {
            if (status.getName() != null && status.getName().length() > nameMaxLength) {
                violations.add(violation(WorkflowCreationViolationType.TOO_LONG_NAME,
                        "Status name exceeds maximum length of " + nameMaxLength + " for status: " + status.getStatusKey()));
            }
        }
        for (CreateWorkflowTransitionDto transition : dto.getTransitions()) {
            if (transition.getName() != null && transition.getName().length() > nameMaxLength) {
                violations.add(violation(WorkflowCreationViolationType.TOO_LONG_NAME,
                        "Transition name exceeds maximum length of " + nameMaxLength + ": from " + transition.getFromStatusKey() + " to " + transition.getToStatusKey()));
            }
        }
        return violations;
    }

    // Передан хотя бы один допустимый issueType, значения не повторяются.
    private List<WorkflowCreationViolation> validateIssueTypes(WorkflowCreationDto dto) {
        List<IssueType> issueTypes = dto.getIssueTypes();

        if (issueTypes == null || issueTypes.isEmpty()) {
            return List.of(violation(WorkflowCreationViolationType.ISSUE_TYPE_LIST_EMPTY,
                    "Issue type list must contain at least one valid issue type"));
        }

        return findDuplicates(issueTypes, Function.identity(),
                issueType -> violation(WorkflowCreationViolationType.DUPLICATE_ISSUE_TYPE, "Duplicate issue type: " + issueType));
    }

    // statusKey каждого статуса не пустой и соответствует формату из конфигурации.
    private List<WorkflowCreationViolation> validateStatusKeys(WorkflowCreationDto dto) {
        List<WorkflowCreationViolation> violations = new ArrayList<>();
        for (CreateWorkflowStatusDto status : dto.getStatuses()) {
            String key = status.getStatusKey();
            if (key == null || key.isBlank()) {
                violations.add(violation(WorkflowCreationViolationType.BLANK_STATUS_KEY, "Status key must not be blank"));
            } else if (!statusKeyPattern.matcher(key).matches()) {
                violations.add(violation(WorkflowCreationViolationType.INVALID_STATUS_KEY_FORMAT,
                        "Status key has invalid format: " + key));
            }
        }
        return violations;
    }

    // statusKey и sortOrder уникальны внутри workflow.
    private List<WorkflowCreationViolation> validateStatusAndSortOrderUniqueness(WorkflowCreationDto dto) {
        List<WorkflowCreationViolation> keyViolations = findDuplicates(dto.getStatuses(),
                CreateWorkflowStatusDto::getStatusKey,
                key -> violation(WorkflowCreationViolationType.DUPLICATE_STATUS_KEY, "Status key is not unique: " + key));

        List<WorkflowCreationViolation> statusOrderViolations = findDuplicates(dto.getStatuses(),
                CreateWorkflowStatusDto::getSortOrder,
                order -> violation(WorkflowCreationViolationType.DUPLICATE_STATUS_SORT_ORDER, "Status sort order is not unique: " + order));

        List<WorkflowCreationViolation> transitionOrderViolations = findDuplicates(dto.getTransitions(),
                CreateWorkflowTransitionDto::getSortOrder,
                order -> violation(WorkflowCreationViolationType.DUPLICATE_TRANSITION_SORT_ORDER, "Transition sort order is not unique: " + order));

        return Stream.of(keyViolations, statusOrderViolations, transitionOrderViolations).flatMap(List::stream).toList();
    }

    // Категория статуса имеет одно из значений: TODO, IN_PROGRESS, DONE.
    private List<WorkflowCreationViolation> validateStatusCategories(WorkflowCreationDto dto) {
        return dto.getStatuses().stream()
                .filter(s -> s.getCategory() == null)
                .map(s -> violation(WorkflowCreationViolationType.INVALID_STATUS_CATEGORY,
                        "Invalid status category for status: " + s.getStatusKey()))
                .toList();
    }

    // Каждый переход ссылается на статусы из создаваемого workflow.
    private List<WorkflowCreationViolation> validateTransitionStatusReferences(WorkflowCreationDto dto) {
        Set<String> statusKeys = dto.getStatuses().stream()
                .map(CreateWorkflowStatusDto::getStatusKey)
                .collect(Collectors.toSet());

        Set<String> reported = new HashSet<>();
        List<WorkflowCreationViolation> violations = new ArrayList<>();
        for (CreateWorkflowTransitionDto transition : dto.getTransitions()) {
            if (!statusKeys.contains(transition.getFromStatusKey()) && reported.add(transition.getFromStatusKey())) {
                violations.add(violation(WorkflowCreationViolationType.TRANSITION_REFERENCES_UNKNOWN_STATUS,
                        "Transition references unknown status key: " + transition.getFromStatusKey()));
            }
            if (!statusKeys.contains(transition.getToStatusKey()) && reported.add(transition.getToStatusKey())) {
                violations.add(violation(WorkflowCreationViolationType.TRANSITION_REFERENCES_UNKNOWN_STATUS,
                        "Transition references unknown status key: " + transition.getToStatusKey()));
            }
        }
        return violations;
    }

    // Переход из статуса в него же запрещён.
    private List<WorkflowCreationViolation> validateNoSelfTransitions(WorkflowCreationDto dto) {
        return dto.getTransitions().stream()
                .filter(t -> t.getFromStatusKey() != null && t.getFromStatusKey().equals(t.getToStatusKey()))
                .map(t -> violation(WorkflowCreationViolationType.SELF_TRANSITION,
                        "Self-transition is not allowed for status: " + t.getFromStatusKey()))
                .toList();
    }

    // Дублирующиеся переходы запрещены.
    private List<WorkflowCreationViolation> validateNoDuplicateTransitions(WorkflowCreationDto dto) {
        List<CreateWorkflowTransitionDto> validTransitions = dto.getTransitions().stream()
                .filter(t -> t.getFromStatusKey() != null && t.getToStatusKey() != null)
                .toList();
        return findDuplicates(validTransitions,
                t -> List.of(t.getFromStatusKey(), t.getToStatusKey()),
                pair -> violation(WorkflowCreationViolationType.DUPLICATE_TRANSITION,
                        "Duplicate transition from '" + pair.get(0) + "' to '" + pair.get(1) + "'"));
    }

    // sortOrder статусов и переходов не меньше нуля.
    private List<WorkflowCreationViolation> validateSortOrders(WorkflowCreationDto dto) {
        List<WorkflowCreationViolation> violations = new ArrayList<>();
        for (CreateWorkflowStatusDto status : dto.getStatuses()) {
            if (status.getSortOrder() < 0) {
                violations.add(violation(WorkflowCreationViolationType.NEGATIVE_SORT_ORDER,
                        "Status sort order must not be negative for status: " + status.getStatusKey()));
            }
        }
        for (CreateWorkflowTransitionDto transition : dto.getTransitions()) {
            if (transition.getSortOrder() < 0) {
                violations.add(violation(WorkflowCreationViolationType.NEGATIVE_SORT_ORDER,
                        "Transition sort order must not be negative: from " + transition.getFromStatusKey() + " to " + transition.getToStatusKey()));
            }
        }
        return violations;
    }

    /**
     * Находит дублирующиеся значения в списке и возвращает нарушение для каждого уникального дубликата.
     * Если одно и то же значение встречается более двух раз, нарушение добавляется только один раз.
     *
     * @param items            список элементов для проверки
     * @param keyExtractor     функция извлечения ключа из элемента
     * @param violationFactory функция создания нарушения по ключу
     */
    private <T, K> List<WorkflowCreationViolation> findDuplicates(List<T> items,
                                                                    Function<T, K> keyExtractor,
                                                                    Function<K, WorkflowCreationViolation> violationFactory) {
        Set<K> seen = new HashSet<>();
        Set<K> reported = new HashSet<>();
        List<WorkflowCreationViolation> violations = new ArrayList<>();

        for (T item : items) {
            K key = keyExtractor.apply(item);
            if (!seen.add(key) && reported.add(key)) {
                violations.add(violationFactory.apply(key));
            }
        }
        return violations;
    }

    private static WorkflowCreationViolation violation(WorkflowCreationViolationType type, String message) {
        return new WorkflowCreationViolation(type, message);
    }
}
