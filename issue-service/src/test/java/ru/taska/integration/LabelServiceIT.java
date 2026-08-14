package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.LabelService;
import ru.taska.service.IssueService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

class LabelServiceIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private LabelService labelService;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ProjectLabelsRepository projectLabelsRepository;

    @Autowired
    private IssueLabelsRepository issueLabelsRepository;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final String REQUEST_ID = "req-label-001";
    private static final String NODE_ID = "issue-service";

    private static final String LABEL_NAME_1 = "Bug";
    private static final String LABEL_NAME_2 = "Feature";
    private static final String LABEL_COLOR_1 = "#FF0000";
    private static final String LABEL_COLOR_2 = "#00FF00";
    private static final String LABEL_COLOR_3 = "#FF00FF";

    private UUID issueId;

    @BeforeEach
    void setUp() {
        issueLabelsRepository.deleteAll().block();
        projectLabelsRepository.deleteAll().block();
        issueRepository.deleteAll().block();

        Issue issue = Issue.builder()
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("TEST-1")
                .issueType(IssueType.TASK)
                .summary("Test issue for labels")
                .description("Test description")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(ADMIN_ID)
                .version(1)
                .build();
        Issue savedIssue = issueRepository.save(issue).block();
        issueId = savedIssue.getId();

        mockProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);
    }

    private void mockProjectRole(ProjectRole role) {
        Mockito.reset(projectServiceStub);
        Mockito.when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(role)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));
    }

    @Test
    @DisplayName("createProjectLabel: ADMIN должен успешно создать метку проекта")
    void shouldCreateProjectLabelByAdmin() {
        // Arrange
        var requestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );

        // Act & Assert with StepVerifier
        StepVerifier.create(
                        labelService.createProjectLabel(REQUEST_ID, NODE_ID, requestDto)
                                .flatMap(created -> {

                                    Assertions.assertThat(created).isNotNull();
                                    Assertions.assertThat(created.id()).isNotNull();

                                    return projectLabelsRepository
                                            .findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                                            .count()
                                            .zipWith(projectLabelsRepository.count())
                                            .zipWith(projectLabelsRepository.findById(created.id()))
                                            .doOnNext(tuple -> {
                                                Long activeCount = tuple.getT1().getT1();
                                                Long allCount = tuple.getT1().getT2();
                                                ProjectLabels label = tuple.getT2();

                                                Assertions.assertThat(activeCount).isEqualTo(1);
                                                Assertions.assertThat(allCount).isEqualTo(1);
                                                Assertions.assertThat(label).isNotNull();
                                                Assertions.assertThat(label.getDeletedAt()).isNull();
                                            })
                                            .thenReturn(created);
                                })
                )
                .expectNextMatches(created -> created.id() != null)
                .verifyComplete();
    }

    @Test
    @DisplayName("createProjectLabel: дубликат метки должен быть отклонен")
    void shouldRejectDuplicateLabel() {
        // Arrange
        var firstRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );

        var duplicateRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_2, ADMIN_ID
        );

        LabelResponses.ProjectLabelInfo firstCreated =
                labelService.createProjectLabel(
                        REQUEST_ID,
                        NODE_ID,
                        firstRequestDto
                ).block();

        Assertions.assertThat(firstCreated).isNotNull();
        Assertions.assertThat(firstCreated.id()).isNotNull();

        ProjectLabels saved =
                projectLabelsRepository
                        .findByIdAndDeletedAtIsNull(firstCreated.id())
                        .block();

        Assertions.assertThat(saved).isNotNull();
        Assertions.assertThat(saved.getName()).isEqualTo(LABEL_NAME_1);
        Assertions.assertThat(saved.getColor()).isEqualTo(LABEL_COLOR_1);

        StepVerifier.create(
                        labelService.createProjectLabel(
                                REQUEST_ID,
                                NODE_ID,
                                duplicateRequestDto
                        )
                )
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error)
                            .isInstanceOf(DomainException.class);

                    DomainException ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus())
                            .isEqualTo(DomainStatus.ALREADY_EXISTS);

                    Assertions.assertThat(ex.getMessage())
                            .contains(
                                    "Label with name '" +
                                            LABEL_NAME_1 +
                                            "' already exists in this project"
                            );
                })
                .verify();
    }

    @Test
    void debugDirectRepositorySave() {

        ProjectLabels label = ProjectLabels.builder()
                .projectId(PROJECT_ID)
                .name("DIRECT_TEST")
                .color("#123456")
                .createdBy(ADMIN_ID)
                .build();

        ProjectLabels saved =
                projectLabelsRepository
                        .save(label)
                        .block();

        Assertions.assertThat(saved).isNotNull();
        Assertions.assertThat(saved.getId()).isNotNull();

        System.out.println("SAVED = " + saved);

        Long count =
                projectLabelsRepository
                        .findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                        .count()
                        .block();

        System.out.println("COUNT = " + count);

        ProjectLabels found =
                projectLabelsRepository
                        .findById(saved.getId())
                        .block();

        System.out.println("FOUND = " + found);
    }



    @Test
    @DisplayName("addIssueLabel: MEMBER должен успешно добавить метку к задаче")
    void shouldAddLabelToIssueByMember() {
        // Arrange - создаем метку с ролью ADMIN
        mockProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);

        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        LabelResponses.ProjectLabelInfo createdLabel = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto
        ).block();

        Assertions.assertThat(createdLabel).isNotNull();
        Assertions.assertThat(createdLabel.id()).isNotNull();

        // Меняем роль на MEMBER для добавления метки
        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        // Act & Assert
        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, createdLabel.id(), MEMBER_ID
        );

        StepVerifier.create(
                        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto)
                                .flatMap(result -> {
                                    Assertions.assertThat(result.issueId()).isEqualTo(addRequestDto.issueId());
                                    Assertions.assertThat(result.labelId()).isEqualTo(addRequestDto.labelId());
                                    Assertions.assertThat(result.createdBy()).isEqualTo(MEMBER_ID);
                                    Assertions.assertThat(result.createdAt()).isNotNull();

                                    return projectLabelsRepository
                                            .findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                                            .count()
                                            .zipWith(issueLabelsRepository.existsByIssueIdAndLabelId(
                                                    addRequestDto.issueId(), addRequestDto.labelId()
                                            ))
                                            .zipWith(issueLabelsRepository.findLabelsByIssueId(addRequestDto.issueId())
                                                    .count()
                                            )
                                            .doOnNext(tuple -> {
                                                Long labelsCount = tuple.getT1().getT1();
                                                Boolean exists = tuple.getT1().getT2();
                                                Long issueLabelsCount = tuple.getT2();

                                                Assertions.assertThat(labelsCount).isEqualTo(1);
                                                Assertions.assertThat(exists).isTrue();
                                                Assertions.assertThat(issueLabelsCount).isEqualTo(1);
                                            })
                                            .thenReturn(result);
                                })
                )
                .expectNextMatches(result ->
                        result.issueId().equals(issueId) &&
                                result.labelId() != null &&
                                result.createdBy().equals(MEMBER_ID)
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("removeIssueLabel: MEMBER должен успешно удалить метку с задачи")
    void shouldRemoveLabelFromIssueByMember() {
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        LabelResponses.ProjectLabelInfo created = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto
        ).block();


        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);
        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, created.id(), MEMBER_ID
        );
        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto).block();

        var removeRequestDto = new LabelCommands.RemoveIssueLabelRequestDto(
                issueId, created.id(), MEMBER_ID
        );

        StepVerifier.create(labelService.removeIssueLabel(REQUEST_ID, NODE_ID, removeRequestDto))
                .assertNext(dto -> {
                    Assertions.assertThat(dto.issueId()).isEqualTo(issueId);
                    Assertions.assertThat(dto.labelId()).isEqualTo(created.id());
                })
                .verifyComplete();

        Boolean exists = issueLabelsRepository.existsByIssueIdAndLabelId(issueId, created.id())
                .block();
        Assertions.assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("listProjectLabels: VIEWER должен получить список меток проекта")
    void shouldListProjectLabelsByViewer() {
        // Arrange
        var requestDto1 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        var requestDto2 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_2, LABEL_COLOR_2, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, requestDto1).block();
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, requestDto2).block();

        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        // Act
        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(
                PROJECT_ID, VIEWER_ID
        );
        Mono<LabelResponses.ListProjectLabelResponseDto> result = labelService.listProjectLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(dto -> {
                    Assertions.assertThat(dto.totalCount()).isEqualTo(2);
                    Assertions.assertThat(dto.labels()).hasSize(2);
                    Assertions.assertThat(dto.labels())
                            .extracting(LabelResponses.ProjectLabelInfo::name)
                            .containsExactlyInAnyOrder(LABEL_NAME_1, LABEL_NAME_2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssues: фильтр по label должен возвращать только задачи с этой меткой")
    void shouldListIssuesFilteredByLabel() {
        // Arrange
        Issue issue2 = Issue.builder()
                .projectId(PROJECT_ID)
                .issueNumber(2)
                .issueKey("TEST-2")
                .issueType(IssueType.TASK)
                .summary("Second test issue")
                .description("Second description")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(ADMIN_ID)
                .version(1)
                .build();
        Issue savedIssue2 = issueRepository.save(issue2).block();
        UUID issueId2 = savedIssue2.getId();

        var createRequestDto1 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        var createRequestDto2 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_2, LABEL_COLOR_2, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto1).block();
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto2).block();

        ProjectLabels label1 = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .filter(l -> l.getName().equals(LABEL_NAME_1))
                .blockFirst();
        ProjectLabels label2 = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .filter(l -> l.getName().equals(LABEL_NAME_2))
                .blockFirst();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        var addRequestDto1 = new LabelCommands.AddIssueLabelRequestDto(
                issueId, label1.getId(), MEMBER_ID
        );
        var addRequestDto2 = new LabelCommands.AddIssueLabelRequestDto(
                issueId2, label2.getId(), MEMBER_ID
        );
        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto1).block();
        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto2).block();

        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        // Act
        Mono<ru.taska.domain.PageResult<Issue>> result = issueService.listIssues(
                REQUEST_ID, NODE_ID, PROJECT_ID, VIEWER_ID,
                null, null, label1.getId(), 0, 10
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(pageResult -> {
                    Assertions.assertThat(pageResult.totalCount()).isEqualTo(1);
                    Assertions.assertThat(pageResult.items()).hasSize(1);
                    Assertions.assertThat(pageResult.items().get(0).getId()).isEqualTo(issueId);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("listProjectLabels: VIEWER может читать метки проекта")
    void viewerCanReadLabels() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        // Act
        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(
                PROJECT_ID, VIEWER_ID
        );
        Mono<LabelResponses.ListProjectLabelResponseDto> result = labelService.listProjectLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(dto -> {
                    Assertions.assertThat(dto.totalCount()).isEqualTo(1);
                    Assertions.assertThat(dto.labels().get(0).name()).isEqualTo(LABEL_NAME_1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("deleteProjectLabel: MEMBER не может удалить метку проекта (только ADMIN)")
    void memberCannotDeleteProjectLabel() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        LabelResponses.ProjectLabelInfo created = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto
        ).block();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        // Act
        var deleteRequestDto = new LabelCommands.DeleteProjectLabelRequestDto(
                created.id(), PROJECT_ID, MEMBER_ID
        );
        Mono<LabelResponses.DeleteProjectLabelResponseDto> result = labelService.deleteProjectLabel(
                REQUEST_ID, NODE_ID, deleteRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus())
                            .isIn(DomainStatus.PERMISSION_DENIED, DomainStatus.FAILED_PRECONDITION);
                })
                .verify();

        ProjectLabels label = projectLabelsRepository.findByIdAndDeletedAtIsNull(created.id())
                .block();
        Assertions.assertThat(label).isNotNull();
        Assertions.assertThat(label.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("deleteProjectLabel: soft-delete должен скрыть метку из запросов")
    void softDeleteShouldHideLabelFromQueries() {

        mockProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );

        LabelResponses.ProjectLabelInfo created = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto
        ).block();

        // Act
        mockProjectRole(ProjectRole.PROJECT_ROLE_ADMIN);

        var deleteRequestDto = new LabelCommands.DeleteProjectLabelRequestDto(
                created.id(), PROJECT_ID, ADMIN_ID
        );
        labelService.deleteProjectLabel(REQUEST_ID, NODE_ID, deleteRequestDto).block();

        // Assert
        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(
                PROJECT_ID, ADMIN_ID
        );
        Mono<LabelResponses.ListProjectLabelResponseDto> result = labelService.listProjectLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        StepVerifier.create(result)
                .assertNext(dto -> {
                    Assertions.assertThat(dto.totalCount()).isEqualTo(0);
                    Assertions.assertThat(dto.labels()).isEmpty();
                })
                .verifyComplete();

        ProjectLabels label = projectLabelsRepository.findById(created.id()).block();
        Assertions.assertThat(label).isNotNull();
        Assertions.assertThat(label.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("listIssueLabels: VIEWER может получить метки задачи")
    void viewerCanGetIssueLabels() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        ProjectLabels label = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .blockFirst();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, label.getId(), MEMBER_ID
        );
        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto).block();

        // Act
        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        var listRequestDto = new LabelCommands.ListIssueLabelsRequestDto(
                issueId, VIEWER_ID
        );
        Mono<LabelResponses.ListIssueLabelResponseDto> result = labelService.listIssueLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(dto -> {
                    Assertions.assertThat(dto.labels()).hasSize(1);
                    Assertions.assertThat(dto.labels().get(0).name()).isEqualTo(LABEL_NAME_1);
                    Assertions.assertThat(dto.labels().get(0).color()).isEqualTo(LABEL_COLOR_1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("addIssueLabel: должна быть ошибка, если задача не найдена")
    void addLabelToNonExistentIssueShouldThrowError() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        ProjectLabels label = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .blockFirst();

        UUID nonExistentIssueId = UUID.randomUUID();

        // Act
        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                nonExistentIssueId, label.getId(), MEMBER_ID
        );
        Mono<LabelResponses.AddIssueLabelResponseDto> result = labelService.addIssueLabel(
                REQUEST_ID, NODE_ID, addRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("Issue not found");
                })
                .verify();
    }

    @Test
    @DisplayName("addIssueLabel: должна быть ошибка, если метка не найдена")
    void addNonExistentLabelShouldThrowError() {
        // Arrange
        UUID nonExistentLabelId = UUID.randomUUID();

        // Act
        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, nonExistentLabelId, MEMBER_ID
        );
        Mono<LabelResponses.AddIssueLabelResponseDto> result = labelService.addIssueLabel(
                REQUEST_ID, NODE_ID, addRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("Label not found");
                })
                .verify();
    }

    @Test
    @DisplayName("addIssueLabel: повторное добавление метки должно быть отклонено")
    void addLabelTwiceShouldReject() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        ProjectLabels label = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .blockFirst();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, label.getId(), MEMBER_ID
        );
        labelService.addIssueLabel(REQUEST_ID, NODE_ID, addRequestDto).block();

        // Act
        Mono<LabelResponses.AddIssueLabelResponseDto> result = labelService.addIssueLabel(
                REQUEST_ID, NODE_ID, addRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("already added to this issue");
                })
                .verify();
    }

    @Test
    @DisplayName("removeIssueLabel: удаление не привязанной метки должно быть отклонено")
    void removeNotAttachedLabelShouldReject() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        ProjectLabels label = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID)
                .blockFirst();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        // Act
        var removeRequestDto = new LabelCommands.RemoveIssueLabelRequestDto(
                issueId, label.getId(), MEMBER_ID
        );
        Mono<LabelResponses.RemoveIssueLabelResponseDto> result = labelService.removeIssueLabel(
                REQUEST_ID, NODE_ID, removeRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("Label not attached to this issue");
                })
                .verify();
    }


    @Test
    @DisplayName("listProjectLabels: проект без меток должен вернуть NOT_FOUND")
    void projectWithoutLabelsShouldReturnNotFound() {
        // Arrange
        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(
                PROJECT_ID_2, VIEWER_ID
        );

        // Act
        Mono<LabelResponses.ListProjectLabelResponseDto> result = labelService.listProjectLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("No labels for project");
                })
                .verify();
    }

    @Test
    @DisplayName("updateProjectLabel: ADMIN должен успешно обновить метку")
    void adminShouldUpdateProjectLabel() {
        // Arrange — создаем метку
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        LabelResponses.ProjectLabelInfo created = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto
        ).block();

        // Act
        var updateRequestDto = new LabelCommands.UpdateProjectLabelRequestDto(
                created.id(), PROJECT_ID, LABEL_NAME_2, LABEL_COLOR_2, ADMIN_ID
        );
        Mono<LabelResponses.ProjectLabelInfo> result = labelService.updateProjectLabel(
                REQUEST_ID, NODE_ID, updateRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .assertNext(info -> {
                    Assertions.assertThat(info.id()).isEqualTo(created.id());
                    Assertions.assertThat(info.name()).isEqualTo(LABEL_NAME_2);
                    Assertions.assertThat(info.color()).isEqualTo(LABEL_COLOR_2);
                })
                .verifyComplete();

        ProjectLabels label = projectLabelsRepository.findByIdAndDeletedAtIsNull(created.id())
                .block();
        Assertions.assertThat(label).isNotNull();
        Assertions.assertThat(label.getName()).isEqualTo(LABEL_NAME_2);
        Assertions.assertThat(label.getColor()).isEqualTo(LABEL_COLOR_2);
    }

    @Test
    @DisplayName("updateProjectLabel: обновление на занятое имя должно быть отклонено")
    void updateLabelToDuplicateNameShouldReject() {
        // Arrange
        var createRequestDto1 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        var createRequestDto2 = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME_2, LABEL_COLOR_2, ADMIN_ID
        );
        LabelResponses.ProjectLabelInfo created1 = labelService.createProjectLabel(
                REQUEST_ID, NODE_ID, createRequestDto1
        ).block();
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto2).block();

        // Act
        var updateRequestDto = new LabelCommands.UpdateProjectLabelRequestDto(
                created1.id(), PROJECT_ID, LABEL_NAME_2, LABEL_COLOR_3, ADMIN_ID
        );
        Mono<LabelResponses.ProjectLabelInfo> result = labelService.updateProjectLabel(
                REQUEST_ID, NODE_ID, updateRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("already exists in this project");
                })
                .verify();
    }

    @Test
    @DisplayName("listIssueLabels: задача без меток должна вернуть NOT_FOUND")
    void issueWithoutLabelsShouldReturnNotFound() {
        // Arrange
        mockProjectRole(ProjectRole.PROJECT_ROLE_VIEWER);

        var listRequestDto = new LabelCommands.ListIssueLabelsRequestDto(
                issueId, VIEWER_ID
        );

        // Act
        Mono<LabelResponses.ListIssueLabelResponseDto> result = labelService.listIssueLabels(
                REQUEST_ID, NODE_ID, listRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("No labels for issue");
                })
                .verify();
    }

    @Test
    @DisplayName("addIssueLabel: метка из другого проекта должна быть отклонена")
    void labelFromDifferentProjectShouldReject() {
        // Arrange
        var createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID_2, LABEL_NAME_1, LABEL_COLOR_1, ADMIN_ID
        );
        labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto).block();

        ProjectLabels label = projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(PROJECT_ID_2)
                .blockFirst();

        mockProjectRole(ProjectRole.PROJECT_ROLE_MEMBER);

        // Act
        var addRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                issueId, label.getId(), MEMBER_ID
        );
        Mono<LabelResponses.AddIssueLabelResponseDto> result = labelService.addIssueLabel(
                REQUEST_ID, NODE_ID, addRequestDto
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getMessage()).contains("does not belong to issue's project");
                })
                .verify();
    }
}
