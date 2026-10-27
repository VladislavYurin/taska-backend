package ru.taska.service;

import client.ProjectIssueTypes;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import nullable.NullableField;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectIssueTypeRepository;

@Service
@RequiredArgsConstructor
public class ProjectIssueTypeService {
    public static final Set<String> COLORS = Set.of("blue", "red", "green", "amber", "purple", "teal", "slate");
    public static final Set<String> ICONS = Set.of("check-square", "bug", "book-open", "headset", "megaphone", "file-text", "flag", "layers");
    public static final Set<String> SHAPES = Set.of("square", "circle", "diamond");
    private final ProjectIssueTypeRepository repository;
    private final ProjectService projectService;
    private final ProjectWriteGuard writeGuard;

    public Flux<ProjectIssueType> list(UUID projectId, UUID actor, String requestId, String nodeId) {
        return projectService.getProject(requestId, nodeId, projectId, actor)
                .thenMany(Flux.defer(() -> repository.list(projectId)));
    }

    /** {@code icon} null means no icon; {@code shape} null means square. */
    @Transactional
    public Mono<ProjectIssueType> create(UUID projectId, UUID actor, String code, String name,
                                         String color, String icon, String shape, Integer sortOrder) {
        return Mono.defer(() -> {
            validateCode(code);
            ProjectIssueType type = validate(new ProjectIssueType(projectId, code, name, color, icon,
                    shape == null ? ProjectIssueTypes.DEFAULT_SHAPE : shape, sortOrder == null ? 100 : sortOrder, null, null));
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> repository.insert(type)))
                    .flatMap(saved -> repository.syncLegacySettings(projectId, actor).thenReturn(saved));
        }).onErrorMap(DuplicateKeyException.class, e -> new DomainException(DomainStatus.ALREADY_EXISTS,
                "Issue type " + code + " already exists in this project"));
    }

    /**
     * Null {@code name}, {@code color}, {@code shape} and {@code sortOrder} stay unchanged. {@code icon} is
     * tri-state: absent stays unchanged, present null removes the icon, present value replaces it.
     */
    @Transactional
    public Mono<ProjectIssueType> update(UUID projectId, UUID actor, String code, String name, String color,
                                         NullableField<String> icon, String shape, Integer sortOrder) {
        return Mono.defer(() -> {
            validateCode(code);
            if (name == null && color == null && !icon.present() && shape == null && sortOrder == null) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "At least one field is required"));
            }
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> requireType(projectId, code)))
                    .map(old -> validate(new ProjectIssueType(projectId, code,
                            name == null ? old.name() : name, color == null ? old.color() : color,
                            icon.orElse(old.icon()), shape == null ? old.shape() : shape,
                            sortOrder == null ? old.sortOrder() : sortOrder,
                            old.createdAt(), old.updatedAt(), old.generation())))
                    .flatMap(repository::update)
                    .flatMap(saved -> repository.syncLegacySettings(projectId, actor).thenReturn(saved));
        });
    }

    @Transactional
    public Mono<java.util.List<ProjectIssueType>> reorder(UUID projectId, UUID actor, java.util.List<String> codes) {
        return Mono.defer(() -> {
            if (codes == null || codes.isEmpty() || codes.stream().distinct().count() != codes.size()) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "codes must be a complete permutation without duplicates"));
            }
            codes.forEach(ProjectIssueTypeService::validateCode);
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> repository.list(projectId).collectList()))
                    .flatMap(current -> {
                        if (!current.stream().map(ProjectIssueType::code).collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(codes))) {
                            return Mono.error(new DomainException(DomainStatus.ABORTED, "Issue type registry changed; reload the current types before reordering",
                                    Map.of("reason", "ISSUE_TYPES_CHANGED")));
                        }
                        return repository.reorder(projectId, codes).then(repository.syncLegacySettings(projectId, actor))
                                .then(repository.list(projectId).collectList());
                    });
        });
    }

    private Mono<Void> authorizeWrite(UUID projectId, UUID actor) {
        return repository.lockProject(projectId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found")))
                .then(Mono.defer(() -> repository.lockActorRole(projectId, actor)))
                .filter(role -> role == ProjectRole.ADMIN)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Project ADMIN role is required")))
                .then(Mono.defer(() -> writeGuard.check(projectId, actor)));
    }

    private Mono<ProjectIssueType> requireType(UUID projectId, String code) {
        return repository.find(projectId, code).switchIfEmpty(Mono.error(
                new DomainException(DomainStatus.NOT_FOUND, "Issue type not found in this project")));
    }

    private static void validateCode(String code) {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid issue type code");
        }
    }

    private ProjectIssueType validate(ProjectIssueType type) {
        if (type.name() == null || type.name().isBlank() || type.name().length() > 50
                || !COLORS.contains(type.color() == null ? "" : type.color())
                || type.icon() != null && !ICONS.contains(type.icon())
                || !SHAPES.contains(type.shape() == null ? "" : type.shape()) || type.sortOrder() < 0) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid issue type name, color, icon, shape or sortOrder");
        }
        return new ProjectIssueType(type.projectId(), type.code(), type.name().strip(), type.color(),
                type.icon(), type.shape(), type.sortOrder(), type.createdAt(), type.updatedAt(), type.generation());
    }
}
