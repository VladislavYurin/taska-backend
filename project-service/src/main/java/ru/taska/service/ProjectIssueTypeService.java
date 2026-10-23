package ru.taska.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;

@Service
@RequiredArgsConstructor
public class ProjectIssueTypeService {
    public static final Set<String> COLORS = Set.of("blue", "red", "green", "amber", "purple", "teal", "slate");
    public static final Set<String> ICONS = Set.of("check-square", "bug", "book-open", "headset", "megaphone", "file-text", "flag", "layers");
    private final ProjectIssueTypeRepository repository;
    private final ProjectService projectService;
    private final GrpcIssueServiceClient issueClient;

    public Flux<ProjectIssueType> list(UUID projectId, UUID actor, String requestId, String nodeId) {
        return projectService.getProject(requestId, nodeId, projectId, actor)
                .thenMany(Flux.defer(() -> repository.list(projectId)));
    }

    @Transactional
    public Mono<ProjectIssueType> create(UUID projectId, UUID actor, String code, String name,
                                         String color, String icon, Integer sortOrder) {
        return Mono.defer(() -> {
            validateCode(code);
            ProjectIssueType type = validate(new ProjectIssueType(projectId, code, name, color, icon,
                    sortOrder == null ? 100 : sortOrder, null, null));
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> repository.insert(type)))
                    .flatMap(saved -> repository.syncLegacySettings(projectId, actor).thenReturn(saved));
        }).onErrorMap(DuplicateKeyException.class, e -> new DomainException(DomainStatus.ALREADY_EXISTS,
                "Issue type " + code + " already exists in this project"));
    }

    @Transactional
    public Mono<ProjectIssueType> update(UUID projectId, UUID actor, String code, String name,
                                         String color, String icon, Integer sortOrder) {
        return Mono.defer(() -> {
            validateCode(code);
            if (name == null && color == null && icon == null && sortOrder == null) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "At least one field is required"));
            }
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> requireType(projectId, code)))
                    .map(old -> validate(new ProjectIssueType(projectId, code,
                            name == null ? old.name() : name, color == null ? old.color() : color,
                            icon == null ? old.icon() : icon, sortOrder == null ? old.sortOrder() : sortOrder,
                            old.createdAt(), old.updatedAt())))
                    .flatMap(repository::update)
                    .flatMap(saved -> repository.syncLegacySettings(projectId, actor).thenReturn(saved));
        });
    }

    @Transactional
    public Mono<Void> delete(UUID projectId, UUID actor, String code, String requestId, String nodeId) {
        return Mono.defer(() -> {
            validateCode(code);
            return authorizeWrite(projectId, actor).then(Mono.defer(() -> requireType(projectId, code)))
                    .then(Mono.defer(() -> repository.list(projectId).count()))
                    .flatMap(count -> count <= 1
                            ? Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Cannot delete the last issue type"))
                            : issueClient.countIssueTypeUsage(projectId, code, requestId, nodeId))
                    .flatMap(count -> count > 0
                            ? Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                    "Issue type " + code + " has " + count + " issues", Map.of("issueCount", count.toString())))
                            : repository.delete(projectId, code))
                    .then(Mono.defer(() -> repository.syncLegacySettings(projectId, actor)));
        });
    }

    private Mono<Void> authorizeWrite(UUID projectId, UUID actor) {
        return repository.lockProject(projectId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found")))
                .then(Mono.defer(() -> repository.lockActorRole(projectId, actor)))
                .filter(role -> role == ProjectRole.ADMIN)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Project ADMIN role is required")))
                .then();
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
                || !ICONS.contains(type.icon() == null ? "" : type.icon()) || type.sortOrder() < 0) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid issue type name, color, icon or sortOrder");
        }
        return new ProjectIssueType(type.projectId(), type.code(), type.name().strip(), type.color(),
                type.icon(), type.sortOrder(), type.createdAt(), type.updatedAt());
    }
}
