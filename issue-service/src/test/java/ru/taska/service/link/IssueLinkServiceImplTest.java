package ru.taska.service.link;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.IssueLinkInfoDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueLinkServiceImplTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SOURCE_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TARGET_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID SECOND_TARGET_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String DELETED_AT = "2007-01-01T01:00:00Z";

    @Mock
    private IssueLinkExecutor executor;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueLinkRepository issueLinkRepository;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssueProperties properties;

    @Mock
    private IssueProperties.AllowedRoles allowedRoles;

    @InjectMocks
    private IssueLinkServiceImpl linkService;

    private Issue issue;
    private IssueLink link;
    private IssueLink secondLink;
    private IssueLinkInfoDto sourceLinkInfo;
    private IssueLinkInfoDto targetLinkInfo;

    @BeforeEach
    void setUp() {
        issue = Issue.builder()
                .id(SOURCE_ISSUE_ID)
                .projectId(PROJECT_ID)
                .build();

        link = IssueLink.builder()
                .id(LINK_ID)
                .projectId(PROJECT_ID)
                .sourceIssueId(SOURCE_ISSUE_ID)
                .targetIssueId(TARGET_ISSUE_ID)
                .linkType(IssueLinkType.RELATES_TO)
                .createdBy(ACTOR_USER_ID)
                .build();

        secondLink = IssueLink.builder()
                .id(SECOND_LINK_ID)
                .projectId(PROJECT_ID)
                .sourceIssueId(SOURCE_ISSUE_ID)
                .targetIssueId(SECOND_TARGET_ISSUE_ID)
                .linkType(IssueLinkType.BLOCKS)
                .createdBy(ACTOR_USER_ID)
                .build();

        sourceLinkInfo = new IssueLinkInfoDto(SOURCE_ISSUE_ID, PROJECT_ID);
        targetLinkInfo = new IssueLinkInfoDto(TARGET_ISSUE_ID, PROJECT_ID);

        Mockito.lenient().when(properties.allowedRoles())
                .thenReturn(allowedRoles);

        Mockito.lenient().when(allowedRoles.listIssueLinksRoles())
                .thenReturn(Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER));

        Mockito.lenient().when(allowedRoles.createIssueLinksRoles())
                .thenReturn(Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER));

        Mockito.lenient().when(allowedRoles.deleteIssueLinksRoles())
                .thenReturn(Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER));

        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()
                ))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Должен вернуть список всех связей задачи")
    void listIssueLinks_shouldSuccessfullyReturnsIssueLinks() {
        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.just(issue));

        Mockito.when(issueLinkRepository.findAllByIssueId(Mockito.any(UUID.class)))
                .thenReturn(Flux.just(link, secondLink));

        StepVerifier.create(linkService.listIssueLinks(REQUEST_ID, NODE_ID, SOURCE_ISSUE_ID, ACTOR_USER_ID))
                .assertNext(result -> Assertions.assertThat(result).isEqualTo(link))
                .assertNext(result -> Assertions.assertThat(result).isEqualTo(secondLink))
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveById(SOURCE_ISSUE_ID);
        Mockito.verify(properties).allowedRoles();
        Mockito.verify(allowedRoles).listIssueLinksRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                Mockito.eq(REQUEST_ID),
                Mockito.eq(NODE_ID),
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID),
                Mockito.anySet()
        );
        Mockito.verify(issueLinkRepository).findAllByIssueId(SOURCE_ISSUE_ID);
    }

    @Test
    @DisplayName("Должен бросать исключение DomainException со статусом NOT_FOUND, если задача не найдена")
    void listIssueLinks_shouldThrowsException_whenIssueNotFound() {
        Mockito.when(issueRepository.findActiveById(Mockito.any(UUID.class)))
                .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found")));

        StepVerifier.create(linkService.listIssueLinks(REQUEST_ID, NODE_ID, SOURCE_ISSUE_ID, ACTOR_USER_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository).findActiveById(SOURCE_ISSUE_ID);
        Mockito.verifyNoMoreInteractions(properties, allowedRoles, projectRoleChecker, issueLinkRepository);
    }

    @Test
    @DisplayName("Должен успешно создать новую связь")
    void createIssueLink_shouldSuccessfullyCreateLink() {
        Mockito.when(issueRepository.findIssueLinkInfo(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Flux.just(sourceLinkInfo, targetLinkInfo));

        Mockito.when(executor.executeLinkCreation(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.any(IssueLinkType.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(Mono.just(link));

        StepVerifier.create(linkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                ))
                .assertNext(result ->
                        Assertions.assertThat(result).isEqualTo(link)
                )
                .verifyComplete();

        Mockito.verify(issueRepository).findIssueLinkInfo(SOURCE_ISSUE_ID, TARGET_ISSUE_ID);
        Mockito.verify(properties).allowedRoles();
        Mockito.verify(allowedRoles).createIssueLinksRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                Mockito.eq(REQUEST_ID),
                Mockito.eq(NODE_ID),
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID),
                Mockito.anySet()
        );
        Mockito.verify(executor).executeLinkCreation(
                REQUEST_ID,
                NODE_ID,
                PROJECT_ID,
                SOURCE_ISSUE_ID,
                TARGET_ISSUE_ID,
                IssueLinkType.RELATES_TO,
                ACTOR_USER_ID
        );
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом INVALID_ARGUMENT при попытке создать связь на эту же задачу")
    void createIssueLink_shouldThrowsException_whenIssuesAreTheSame() {
        StepVerifier.create(linkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        SOURCE_ISSUE_ID,
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                })
                .verify();

        Mockito.verifyNoMoreInteractions(issueRepository, properties, allowedRoles, projectRoleChecker, executor);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом NOT_FOUND, если исходная задача не найдена")
    void createIssueLink_shouldThrowsException_whenSourceIssueNotFound() {
        Mockito.when(issueRepository.findIssueLinkInfo(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Flux.just(targetLinkInfo));

        StepVerifier.create(linkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository).findIssueLinkInfo(SOURCE_ISSUE_ID, TARGET_ISSUE_ID);
        Mockito.verifyNoMoreInteractions(properties, allowedRoles, projectRoleChecker, executor);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом NOT_FOUND, если целевая задача не найдена")
    void createIssueLink_shouldThrowsException_whenTargetIssueNotFound() {
        Mockito.when(issueRepository.findIssueLinkInfo(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Flux.just(sourceLinkInfo));

        StepVerifier.create(linkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository).findIssueLinkInfo(SOURCE_ISSUE_ID, TARGET_ISSUE_ID);
        Mockito.verifyNoMoreInteractions(properties, allowedRoles, projectRoleChecker, executor);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом INVALID_ARGUMENT, если задачи с разных проектов")
    void createIssueLink_shouldThrowsException_whenIssuesBelongDifferentProjects() {
        var wrongIssueLinkInfo = new IssueLinkInfoDto(TARGET_ISSUE_ID, UUID.fromString("00000000-0000-0000-0000-000000000008"));

        Mockito.when(issueRepository.findIssueLinkInfo(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Flux.just(sourceLinkInfo, wrongIssueLinkInfo));

        StepVerifier.create(linkService.createIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        TARGET_ISSUE_ID,
                        IssueLinkType.RELATES_TO,
                        ACTOR_USER_ID
                ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                })
                .verify();

        Mockito.verify(issueRepository).findIssueLinkInfo(SOURCE_ISSUE_ID, TARGET_ISSUE_ID);
        Mockito.verifyNoMoreInteractions(properties, allowedRoles, projectRoleChecker, executor);
    }

    @Test
    @DisplayName("Должен успешно выполнить мягкое удаление")
    void deleteIssueLink_shouldSuccessfullyCompletedSoftDeletion() {
        var deletedLink = link.toBuilder()
                .deletedAt(Instant.parse(DELETED_AT))
                .build();

        Mockito.when(issueLinkRepository.findActiveByIdAndIssueId(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Mono.just(link));

        Mockito.when(executor.executeLinkDeletion(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class)
                ))
                .thenReturn(Mono.just(deletedLink));

        StepVerifier.create(linkService.deleteIssueLink(
                        REQUEST_ID,
                        NODE_ID,
                        SOURCE_ISSUE_ID,
                        LINK_ID,
                        ACTOR_USER_ID
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.getId()).isEqualTo(LINK_ID);
                    Assertions.assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
                    Assertions.assertThat(result.getSourceIssueId()).isEqualTo(SOURCE_ISSUE_ID);
                    Assertions.assertThat(result.getTargetIssueId()).isEqualTo(TARGET_ISSUE_ID);
                    Assertions.assertThat(result.getLinkType()).isEqualTo(IssueLinkType.RELATES_TO);
                    Assertions.assertThat(result.getCreatedBy()).isEqualTo(ACTOR_USER_ID);
                    Assertions.assertThat(result.getDeletedAt()).isEqualTo(Instant.parse(DELETED_AT));
                })
                .verifyComplete();

    Mockito.verify(issueLinkRepository).findActiveByIdAndIssueId(LINK_ID, SOURCE_ISSUE_ID);
        Mockito.verify(executor).executeLinkDeletion(REQUEST_ID, NODE_ID, link.getId(), ACTOR_USER_ID);
    }

    @Test
    @DisplayName("Должен выбросить исключение DomainException со статусом NOT_FOUND, если связь не найдена")
    void deleteIssueLink_shouldThrowsException_whenLinkNotFound() {
        Mockito.when(issueLinkRepository.findActiveByIdAndIssueId(Mockito.any(UUID.class), Mockito.any(UUID.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(linkService.deleteIssueLink(
                REQUEST_ID,
                NODE_ID,
                SOURCE_ISSUE_ID,
                LINK_ID,
                ACTOR_USER_ID
        ))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    var ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueLinkRepository).findActiveByIdAndIssueId(LINK_ID, SOURCE_ISSUE_ID);
        Mockito.verifyNoMoreInteractions(properties, allowedRoles, projectRoleChecker, executor);
    }
}