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

/**
 * Проверяет существование задачи и наличие у пользователя права на её просмотр.
 *
 * <p>Использует идентификатор проекта задачи для проверки роли пользователя
 * через {@link ProjectRoleChecker}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueAccessGuard {

    private final IssueProperties issueProperties;
    private final ProjectRoleChecker projectRoleChecker;

    /**
     * Проверяет существование задачи и наличие у пользователя права на её просмотр.
     *
     * <p>Если задача не найдена, возвращает {@link DomainStatus#NOT_FOUND}.
     * После получения задачи определяется её проект и проверяется роль пользователя.
     * При успешной проверке возвращает исходные данные задачи без изменений.</p>
     *
     * <p>{@code issueLookup} — это источник данных задачи, а не сам объект задачи.
     * Он передаётся вызывающей стороной, поскольку guard не отвечает за получение
     * данных из конкретного источника.
     *
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла сервиса
     * @param issueId идентификатор задачи
     * @param actorUserId идентификатор пользователя, выполняющего запрос
     * @param issueLookup источник данных задачи
     * @param projectIdExtractor функция извлечения идентификатора проекта из данных задачи
     * @param <T> тип данных задачи
     * @return исходные данные задачи после успешной проверки доступа
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
