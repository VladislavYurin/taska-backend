package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class IssueAccessGuard {

    private final IssueProperties issueProperties;
    private final ProjectRoleChecker projectRoleChecker;

    /**
     * Проверяет, что задача существует (issueLookup вернул значение) и что
     * actorUserId обладает одной из разрешённых для чтения задачи ролей
     * в проекте, к которому она относится.
     * <p>
     * Тип проверяемого значения не фиксирован — подходит и для {@code Issue},
     * и для {@code IssueCoreDetails}: вызывающая сторона сама указывает,
     * как извлечь из него projectId.
     */
    public <T> Mono<T> verifyReadAccess(
            String requestId, String nodeId, UUID issueId, UUID actorUserId,
            Mono<T> issueLookup, Function<T, UUID> projectIdExtractor
    ) {
        return issueLookup
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue with id: {} was not found", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + issueId));
                }))
                .flatMap(value -> {
                    UUID projectId = projectIdExtractor.apply(value);
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().getIssueRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                            .thenReturn(value);
                });
    }
}
