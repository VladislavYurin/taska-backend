package ru.taska.service.link;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.IssueLinkInfoDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.Set;
import java.util.UUID;

/**
 * Оркестратор для работы со связями задачи, реализующий {@link IssueLinkService}.
 * Необходим для разделения операций вычисления и сетевых обращений от транзакционных операций.
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueLinkServiceImpl implements IssueLinkService {

    private final IssueLinkExecutor executor;
    private final IssueRepository issueRepository;
    private final IssueLinkRepository issueLinkRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final IssueProperties issueProperties;

    /**
     * Находит в БД активную задачу, связи которой запрашиваются.
     * Проверяет роль пользователя, инициировавшего запрос через {@link ProjectRoleChecker},
     * и в случае успешной проверки возвращает все связи задачи.
     */
    @Override
    public Flux<IssueLink> listIssueLinks(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue not found: id={}", requestId, nodeId, issueId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }))
                .flatMapMany(issue -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().listIssueLinksRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles)
                            .thenMany(issueLinkRepository.findAllByIssueId(issueId));
                })
                .doOnComplete(() ->
                        log.debug("[{}][{}] Links successfully found for issue: issueId={}", requestId, nodeId, issueId)
                );
    }

    /**
     * Создает новую связь между двумя задачами.
     * Запрашивает из БД необходимые данные о задачах, между которыми устанавливается связь.
     * Проверяет роль пользователя, инициировавшего запрос через {@link ProjectRoleChecker},
     * и в случае успешной проверки передает выполнение создания связи
     * в транзакционный блок {@link IssueLinkExecutor#executeLinkCreation}.
     */
    @Override
    public Mono<IssueLink> createIssueLink(
            String requestId,
            String nodeId,
            UUID sourceIssueId,
            UUID targetIssueId,
            IssueLinkType linkType,
            UUID actorUserId
    ) {
        if (sourceIssueId.equals(targetIssueId)) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Issue can't be linked to itself"));
        }

        return loadAndValidateProjectId(requestId, nodeId, sourceIssueId, targetIssueId)
                .flatMap(projectId -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().createIssueLinksRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, actorUserId, allowedRoles)
                            .thenReturn(projectId);
                })
                .flatMap(projectId ->
                        Mono.defer(() ->
                                executor.executeLinkCreation(requestId, nodeId, projectId, sourceIssueId, targetIssueId, linkType, actorUserId)
                        )
                );
    }

    /**
     * Удаляет связь (мягкое удаление).
     * Находит в БД активную связь. Проверяет роль пользователя, инициировавшего запрос через {@link ProjectRoleChecker},
     * и в случае успешной проверки передает выполнение удаления связи
     * в транзакционный блок {@link IssueLinkExecutor#executeLinkDeletion} .
     */
    @Override
    public Mono<IssueLink> deleteIssueLink(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID linkId,
            UUID actorUserId
    ) {
        return issueLinkRepository.findActiveByIdAndIssueId(linkId, issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Link not found: linkId={}, issueId={}", requestId, nodeId, linkId, issueId);

                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue link not found"));
                }))
                .flatMap(link -> {
                    Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().deleteIssueLinksRoles();

                    return projectRoleChecker.checkProjectRole(requestId, nodeId, link.getProjectId(), actorUserId, allowedRoles)
                            .thenReturn(link);
                })
                .flatMap(link ->
                        Mono.defer(() ->
                                executor.executeLinkDeletion(requestId, nodeId, link.getId(), actorUserId)
                        )
                );
    }

    /**
     * Загружает из БД необходимые данные о задачах, между которыми устанавливается связь.
     * Проверяет существование обеих задач в БД и их принадлежность к общему проекту.
     * В случае успешного прохождения всех проверок возвращает идентификатор проекта,
     * необходимый для дальнейшего создания связи между этими задачами.
     *
     * @param requestId     идентификатор запроса
     * @param nodeId        идентификатор узла
     * @param sourceIssueId идентификатор исходной задачи
     * @param targetIssueId идентификатор целевой задачи
     * @return идентификатор проекта
     */
    private Mono<UUID> loadAndValidateProjectId(
            String requestId,
            String nodeId,
            UUID sourceIssueId,
            UUID targetIssueId
    ) {
        return issueRepository.findIssueLinkInfo(sourceIssueId, targetIssueId)
                .collectMap(IssueLinkInfoDto::id)
                .flatMap(map -> {
                    IssueLinkInfoDto source = map.get(sourceIssueId);
                    IssueLinkInfoDto target = map.get(targetIssueId);

                    if (source == null) {
                        log.warn("[{}][{}] Source issue with id: {} not found", requestId, nodeId, sourceIssueId);

                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Source issue not found"));
                    }

                    if (target == null) {
                        log.warn("[{}][{}] Target issue with id: {} not found", requestId, nodeId, targetIssueId);

                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Target issue not found"));
                    }

                    if (!source.projectId().equals(target.projectId())) {
                        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Issues belong different projects"));
                    }

                    return Mono.just(source.projectId());
                });
    }

}
