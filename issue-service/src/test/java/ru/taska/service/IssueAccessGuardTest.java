package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.impl.IssueAccessGuard;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueAccessGuardTest {

    private static final String REQUEST_ID = "req-1";
    private static final String NODE_ID = "node-1";

    private record Probe(UUID projectId) {}

    @Mock
    private IssueProperties issueProperties;
    @Mock
    private IssueProperties.AllowedRoles allowedRolesProperty;
    @Mock
    private ProjectRoleChecker projectRoleChecker;

    private IssueAccessGuard guard;

    private UUID issueId;
    private UUID actorUserId;
    private UUID projectId;
    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    void setUp() {
        guard = new IssueAccessGuard(issueProperties, projectRoleChecker);

        issueId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        allowedRoles = Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN);
    }

    @Nested
    @DisplayName("Позитивные сценарии проверки доступа")
    class PositiveCases {

        @Test
        @DisplayName("Возвращает исходное значение, если задача найдена и роль пользователя разрешена")
        void shouldReturnValueWhenIssueFoundAndRoleAllowed() {
            Probe probe = new Probe(projectId);

            Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRolesProperty);
            Mockito.when(allowedRolesProperty.getIssueRoles()).thenReturn(allowedRoles);
            Mockito.when(projectRoleChecker.checkProjectRole(
                            REQUEST_ID, NODE_ID, projectId, actorUserId, allowedRoles))
                    .thenReturn(Mono.empty());

            Mono<Probe> result = guard.verifyReadAccess(
                    REQUEST_ID, NODE_ID, issueId, actorUserId, Mono.just(probe), Probe::projectId);

            StepVerifier.create(result)
                    .expectNext(probe)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Извлекает projectId через переданный экстрактор, а не напрямую")
        void shouldUseProvidedProjectIdExtractor() {
            Probe probe = new Probe(projectId);

            Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRolesProperty);
            Mockito.when(allowedRolesProperty.getIssueRoles()).thenReturn(allowedRoles);
            Mockito.when(projectRoleChecker.checkProjectRole(
                            REQUEST_ID, NODE_ID, projectId, actorUserId, allowedRoles))
                    .thenReturn(Mono.empty());

            Mono<Probe> result = guard.verifyReadAccess(
                    REQUEST_ID, NODE_ID, issueId, actorUserId, Mono.just(probe), Probe::projectId);

            StepVerifier.create(result).expectNextCount(1).verifyComplete();

            Mockito.verify(projectRoleChecker)
                    .checkProjectRole(REQUEST_ID, NODE_ID, projectId, actorUserId, allowedRoles);
        }
    }

    @Nested
    @DisplayName("Граничные сценарии: отсутствие задачи и запрет доступа")
    class EdgeCases {

        @Test
        @DisplayName("Возвращает NOT_FOUND DomainException, если issueLookup ничего не вернул")
        void shouldReturnNotFoundWhenIssueLookupIsEmpty() {
            Mono<Probe> result = guard.verifyReadAccess(
                    REQUEST_ID, NODE_ID, issueId, actorUserId, Mono.empty(), Probe::projectId);

            StepVerifier.create(result)
                    .expectErrorMatches(error ->
                            error instanceof DomainException domainException
                                    && domainException.getStatus() == DomainStatus.NOT_FOUND
                                    && domainException.getMessage().contains(issueId.toString()))
                    .verify();

            Mockito.verifyNoInteractions(projectRoleChecker);
        }

        @Test
        @DisplayName("Пробрасывает ошибку роли, если projectRoleChecker её вернул")
        void shouldPropagateErrorWhenRoleCheckFails() {
            Probe probe = new Probe(projectId);
            DomainException forbidden = new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied");

            Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRolesProperty);
            Mockito.when(allowedRolesProperty.getIssueRoles()).thenReturn(allowedRoles);
            Mockito.when(projectRoleChecker.checkProjectRole(
                            REQUEST_ID, NODE_ID, projectId, actorUserId, allowedRoles))
                    .thenReturn(Mono.error(forbidden));

            Mono<Probe> result = guard.verifyReadAccess(
                    REQUEST_ID, NODE_ID, issueId, actorUserId, Mono.just(probe), Probe::projectId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == forbidden)
                    .verify();
        }

        @Test
        @DisplayName("Пробрасывает ошибку issueLookup, не подменяя её на NOT_FOUND")
        void shouldPropagateLookupErrorWithoutMaskingAsNotFound() {
            RuntimeException dbFailure = new RuntimeException("connection reset");

            Mono<Probe> result = guard.verifyReadAccess(
                    REQUEST_ID, NODE_ID, issueId, actorUserId, Mono.error(dbFailure), Probe::projectId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == dbFailure)
                    .verify();

            Mockito.verifyNoInteractions(projectRoleChecker);
        }
    }
}
