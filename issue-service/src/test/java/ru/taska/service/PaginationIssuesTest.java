package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.labels.IssueWithLabels;
import ru.taska.domain.dto.labels.ProjectLabelWithIssuesId;
import ru.taska.domain.labels.ProjectLabels;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

class PaginationIssuesTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER,
                ProjectRole.VIEWER
        );

        Mockito.when(issueProperties.allowedRoles().listIssueRoles()).thenReturn(allowedRoles);
        Mockito.lenient().when(issueProperties.pagination().defaultPageSize()).thenReturn(10);
        Mockito.lenient().when(issueProperties.pagination().maxPageSize()).thenReturn(50);
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles)
                )
                .thenReturn(Mono.empty());
    }

    private Issue buildIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueType(IssueType.TASK)
                .summary("Задача")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .build();
    }

    @Test
    @DisplayName("Должен успешно возвращать задачи из репозитория")
    void shouldReturnIssuesFromRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(issue));
        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "TODO", ASSIGNEE_ID, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).extracting(IssueWithLabels::issue)
                            .containsExactly(issue);
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    @DisplayName("Должен корректно передавать null-фильтры в репозиторий")
    void shouldPassNullFiltersToRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(issue));
        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 10)
                )
                .assertNext(result ->
                    Assertions.assertThat(result.items()).extracting(IssueWithLabels::issue)
                            .containsExactly(issue)
                )
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
        Mockito.verify(issueRepository).countByFilter(eq(PROJECT_ID), any(), any());
    }

    @Test
    @DisplayName("Должен возвращать пустую страницу, если репозиторий пуст")
    void shouldReturnEmptyPageWhenRepositoryReturnsEmpty() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    @DisplayName("Должен корректно возвращать множественные задачи")
    void shouldReturnMultipleIssues() {
        Issue first = buildIssue();
        Issue second = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(2L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(first, second));
        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "TODO", null, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithLabels::issue)
                            .containsExactly(first,second);
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку из репозитория наверх по потоку")
    void shouldPropagateErrorFromRepository() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 10)
                )
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().equals("DB error"))
                .verify();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    @DisplayName("Должен рассчитывать корректный сдвиг (offset) для переданной страницы")
    void shouldCalculateCorrectOffsetForPage() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(10L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 2, 5)
                )
                .assertNext(result -> Assertions.assertThat(result.totalCount()).isEqualTo(10))
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен сбрасывать страницу в ноль, если передано отрицательное значение")
    void shouldFallbackToPageZeroWhenPageIsNegative() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, -1, 10)
                )
                .assertNext(result ->
                        Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен откатываться на дефолтный размер страницы, если передан невалидный размер")
    void shouldFallbackToDefaultPageSizeWhenPageSizeIsInvalid() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 0)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен ограничивать размер страницы максимальным лимитом, если передано слишком большое значение")
    void shouldClampToMaxPageSizeWhenPageSizeExceedsLimit() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, Integer.MAX_VALUE)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository)
                .findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен использовать значения по умолчанию, если параметры страницы и ее размера равны null")
    void shouldUseDefaultsWhenPageAndPageSizeAreNull() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, null, null)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    void shouldReturnIssuesWithAllStatusesWhenStatusKeyIsNull() {
        Issue firstIssue = buildIssue();
        Issue secondIssue = buildIssue();
        Issue thirdIssue = buildIssue();

        firstIssue.setStatusKey("TODO");
        secondIssue.setStatusKey("IN_PROGRESS");
        thirdIssue.setStatusKey("DONE");

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        isNull(),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(3L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        isNull(),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.just(firstIssue, secondIssue, thirdIssue));

        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, ASSIGNEE_ID, null, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(result.items())
                            .extracting(issueWithLabels -> issueWithLabels.issue().getStatusKey())
                            .containsExactly("TODO", "IN_PROGRESS", "DONE");
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                isNull(),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                isNull(),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }

    @Test
    void shouldReturnOnlyIssuesWithRequestedArbitraryStatusKey() {
        Issue issue = buildIssue();
        issue.setStatusKey("IN_REVIEW");

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        eq("IN_REVIEW"),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(1L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        eq("IN_REVIEW"),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.just(issue));

        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "IN_REVIEW", ASSIGNEE_ID, null, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).extracting(IssueWithLabels::issue)
                            .containsExactly(issue);
                    Assertions.assertThat(result.items())
                            .extracting(issueWithLabels -> issueWithLabels.issue().getStatusKey())
                            .containsExactly("IN_REVIEW");
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                eq("IN_REVIEW"),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                eq("IN_REVIEW"),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }

    @Test
    void shouldReturnEmptyPageWhenStatusKeyIsUnknownButValidString() {
        String unknownStatusKey = "READY_FOR_TESTING";

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        eq(unknownStatusKey),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(0L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        eq(unknownStatusKey),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        unknownStatusKey, ASSIGNEE_ID, null, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                eq(unknownStatusKey),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                eq(unknownStatusKey),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }

    @Test
    @DisplayName("Должен возвращать задачи с метками")
    void shouldReturnIssuesWithLabels() {
        // Arrange
        Issue issue = buildIssue();

        UUID labelId1 = UUID.randomUUID();
        UUID labelId2 = UUID.randomUUID();

        ProjectLabelWithIssuesId label1 = new ProjectLabelWithIssuesId(
                labelId1,
                PROJECT_ID,
                "test",
                "#AAAAAA",
                REPORTER_ID,
                Instant.now(),
                null,
                issue.getId()
        );

        ProjectLabelWithIssuesId label2 = new ProjectLabelWithIssuesId(
                labelId2,
                PROJECT_ID,
                "test2",
                "#FFFFFF",
                REPORTER_ID,
                Instant.now(),
                null,
                issue.getId()
        );

        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(issue));
        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.just(label1, label2));

        // Act & Assert
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).hasSize(1);

                    IssueWithLabels issueWithLabels = result.items().get(0);
                    Assertions.assertThat(issueWithLabels.issue()).isEqualTo(issue);
                    Assertions.assertThat(issueWithLabels.labels()).hasSize(2);
                    Assertions.assertThat(issueWithLabels.labels())
                            .extracting(ProjectLabels::getName)
                            .containsExactlyInAnyOrder("test", "test2");
                    Assertions.assertThat(issueWithLabels.labels())
                            .extracting(ProjectLabels::getColor)
                            .containsExactlyInAnyOrder("#AAAAAA", "#FFFFFF");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Должен корректно распределить метки по разным задачам")
    void shouldDistributeLabelsCorrectlyAmongIssues() {
        // Arrange
        UUID issueId1 = UUID.randomUUID();
        UUID issueId2 = UUID.randomUUID();

        Issue issue1 = buildIssue();
        issue1.setId(issueId1);
        Issue issue2 = buildIssue();
        issue2.setId(issueId2);

        ProjectLabels label1 = ProjectLabels.builder()
                .id(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .name("bug")
                .color("#FF0000")
                .createdBy(REPORTER_ID)
                .createdAt(Instant.now())
                .build();

        ProjectLabels label2 = ProjectLabels.builder()
                .id(UUID.randomUUID())
                .projectId(PROJECT_ID)
                .name("feature")
                .color("#00FF00")
                .createdBy(REPORTER_ID)
                .createdAt(Instant.now())
                .build();

        ProjectLabelWithIssuesId labelWithIssue1 = new ProjectLabelWithIssuesId(
                label1.getId(), label1.getProjectId(), label1.getName(),
                label1.getColor(), label1.getCreatedBy(), label1.getCreatedAt(),
                label1.getDeletedAt(), issueId1
        );

        ProjectLabelWithIssuesId labelWithIssue2 = new ProjectLabelWithIssuesId(
                label2.getId(), label2.getProjectId(), label2.getName(),
                label2.getColor(), label2.getCreatedBy(), label2.getCreatedAt(),
                label2.getDeletedAt(), issueId2
        );

        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(2L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(issue1, issue2));

        Mockito.when(issueLabelsRepository.findActiveLabelsWithIssueId(any()))
                .thenReturn(Flux.just(labelWithIssue1, labelWithIssue2));

        // Act & Assert
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items()).hasSize(2);

                    IssueWithLabels issue1Result = result.items().stream()
                            .filter(iw -> iw.issue().getId().equals(issueId1))
                            .findFirst()
                            .orElseThrow();
                    Assertions.assertThat(issue1Result.labels()).hasSize(1);
                    Assertions.assertThat(issue1Result.labels().get(0).getName()).isEqualTo("bug");

                    IssueWithLabels issue2Result = result.items().stream()
                            .filter(iw -> iw.issue().getId().equals(issueId2))
                            .findFirst()
                            .orElseThrow();
                    Assertions.assertThat(issue2Result.labels()).hasSize(1);
                    Assertions.assertThat(issue2Result.labels().get(0).getName()).isEqualTo("feature");
                })
                .verifyComplete();
    }
}
