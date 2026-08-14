package ru.taska.service;

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
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.LabelMapper;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.service.impl.LabelServiceImpl;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LabelService Unit Tests")
class LabelServiceImplTest {

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueProperties.AllowedRoles allowedRoles;

    @Mock
    private ProjectLabelsRepository projectLabelsRepository;

    @Mock
    private IssueLabelsRepository issueLabelsRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private LabelMapper mapper;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private PayloadSerializer payloadSerializer;

    @InjectMocks
    private LabelServiceImpl labelService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LABEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String LABEL_NAME = "Bug";
    private static final String LABEL_COLOR = "#FF0000";

    private LabelCommands.CreateProjectLabelRequestDto createRequestDto;
    private LabelCommands.AddIssueLabelRequestDto addIssueLabelRequestDto;
    private ProjectLabels projectLabel;
    private IssueLabels issueLabel;
    private JsonNode mockPayload;

    @BeforeEach
    void setUp() {
        createRequestDto = new LabelCommands.CreateProjectLabelRequestDto(
                PROJECT_ID, LABEL_NAME, LABEL_COLOR, ADMIN_ID
        );

        addIssueLabelRequestDto = new LabelCommands.AddIssueLabelRequestDto(
                ISSUE_ID, LABEL_ID, MEMBER_ID
        );

        projectLabel = ProjectLabels.builder()
                .id(LABEL_ID)
                .projectId(PROJECT_ID)
                .name(LABEL_NAME)
                .color(LABEL_COLOR)
                .createdBy(ADMIN_ID)
                .createdAt(Instant.now())
                .build();

        issueLabel = IssueLabels.builder()
                .id(UUID.randomUUID())
                .issueId(ISSUE_ID)
                .labelId(LABEL_ID)
                .createdBy(MEMBER_ID)
                .createdAt(Instant.now())
                .build();

        mockPayload = mock(JsonNode.class);

    }

    // ===== CREATE PROJECT LABEL TESTS =====

    @Test
    @DisplayName("createProjectLabel: должен успешно создать метку при уникальном имени")
    void shouldCreateProjectLabelWhenNameIsUnique() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.createProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.existsActiveByName(any(), anyString()))
                .thenReturn(Mono.just(false));

        Mockito.when(mapper.toEntity(Mockito.any(LabelCommands.CreateProjectLabelRequestDto.class))).thenReturn(projectLabel);
        Mockito.when(projectLabelsRepository.save(any(ProjectLabels.class))).thenReturn(Mono.just(projectLabel));
        Mockito.when(mapper.toProjectLabelInfo(any(ProjectLabels.class))).thenReturn(
                new LabelResponses.ProjectLabelInfo(LABEL_ID, PROJECT_ID, LABEL_NAME, LABEL_COLOR, ADMIN_ID, Instant.now(), null)
        );

        // Act & Assert
        StepVerifier.create(labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto))
                .expectNextMatches(info -> {
                    Assertions.assertThat(info.id()).isEqualTo(LABEL_ID);
                    Assertions.assertThat(info.name()).isEqualTo(LABEL_NAME);
                    return true;
                })
                .verifyComplete();

        verify(projectLabelsRepository).existsActiveByName(PROJECT_ID, LABEL_NAME);
        verify(projectLabelsRepository).save(any(ProjectLabels.class));
    }

    @Test
    @DisplayName("createProjectLabel: должен выбросить исключение при дубликате имени")
    void shouldThrowExceptionWhenLabelNameAlreadyExists() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.createProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.existsActiveByName(any(), anyString()))
                .thenReturn(Mono.just(true));

        // Act & Assert
        StepVerifier.create(labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.ALREADY_EXISTS);
                    Assertions.assertThat(ex.getMessage()).contains("already exists in this project");
                })
                .verify();

        verify(projectLabelsRepository, never()).save(any(ProjectLabels.class));
    }

    @Test
    @DisplayName("createProjectLabel: должен выбросить исключение при ошибке проверки роли")
    void shouldThrowExceptionWhenRoleCheckFails() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.createProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied")));

        // Act & Assert
        StepVerifier.create(labelService.createProjectLabel(REQUEST_ID, NODE_ID, createRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                })
                .verify();

        verify(projectLabelsRepository, never()).existsActiveByName(any(), anyString());
        verify(projectLabelsRepository, never()).save(any(ProjectLabels.class));
    }

    // ===== ADD ISSUE LABEL TESTS =====

    @Test
    @DisplayName("addIssueLabel: должен успешно добавить метку к задаче")
    void shouldAddLabelToIssueSuccessfully() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(issueLabelsRepository.existsByIssueIdAndLabelId(any(), any()))
                .thenReturn(Mono.just(false));

        Mockito.when(mapper.toEntity(any(LabelCommands.AddIssueLabelRequestDto.class))).thenReturn(issueLabel);
        Mockito.when(issueLabelsRepository.save(any(IssueLabels.class))).thenReturn(Mono.just(issueLabel));

        Mockito.when(payloadSerializer.createLabelAddedPayload(any(ProjectLabels.class))).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(labelService.addIssueLabel(REQUEST_ID, NODE_ID, addIssueLabelRequestDto))
                .expectNextMatches(response -> {
                    Assertions.assertThat(response.issueId()).isEqualTo(ISSUE_ID);
                    Assertions.assertThat(response.labelId()).isEqualTo(LABEL_ID);
                    return true;
                })
                .verifyComplete();

        verify(issueLabelsRepository).save(any(IssueLabels.class));
        verify(issueHistoryService).saveIssueHistory(eq(REQUEST_ID), eq(NODE_ID), eq(ISSUE_ID), eq(MEMBER_ID),
                eq(IssueEventType.LABEL_ADDED), any(JsonNode.class));
        verify(outboxEventService).saveOutboxEvent(eq(REQUEST_ID), eq(NODE_ID), eq(AggregateType.ISSUE),
                eq(ISSUE_ID), eq(EventType.ISSUE_LABEL_ADDED), any(JsonNode.class));
    }

    @Test
    @DisplayName("addIssueLabel: должен выбросить исключение, если метка уже добавлена")
    void shouldThrowExceptionWhenLabelAlreadyAdded() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(issueLabelsRepository.existsByIssueIdAndLabelId(any(), any()))
                .thenReturn(Mono.just(true));

        // Act & Assert
        StepVerifier.create(labelService.addIssueLabel(REQUEST_ID, NODE_ID, addIssueLabelRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.ALREADY_EXISTS);
                    Assertions.assertThat(ex.getMessage()).contains("already added to this issue");
                })
                .verify();

        verify(issueLabelsRepository, never()).save(any(IssueLabels.class));
    }

    @Test
    @DisplayName("addIssueLabel: должен выбросить исключение, если задача не найдена")
    void shouldThrowExceptionWhenIssueNotFound() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        Mockito.when(issueRepository.findActiveById(any()))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(labelService.addIssueLabel(REQUEST_ID, NODE_ID, addIssueLabelRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue not found");
                })
                .verify();

        verify(projectLabelsRepository, never()).findByIdAndDeletedAtIsNull(any());
        verify(issueLabelsRepository, never()).save(any(IssueLabels.class));
    }

    @Test
    @DisplayName("addIssueLabel: должен выбросить исключение, если метка не найдена")
    void shouldThrowExceptionWhenLabelNotFound() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.addIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(labelService.addIssueLabel(REQUEST_ID, NODE_ID, addIssueLabelRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Label not found");
                })
                .verify();

        verify(issueLabelsRepository, never()).existsByIssueIdAndLabelId(any(), any());
        verify(issueLabelsRepository, never()).save(any(IssueLabels.class));
    }

    // ===== LIST PROJECT LABELS TESTS =====

    @Test
    @DisplayName("listProjectLabels: должен успешно вернуть список меток")
    void shouldReturnListOfProjectLabels() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listProjectLabelRoles()).thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(any()))
                .thenReturn(Flux.just(projectLabel));

        Mockito.when(mapper.toListProjectLabelResponseDto(anyList())).thenReturn(
                LabelResponses.ListProjectLabelResponseDto.of(
                        java.util.List.of(
                                new LabelResponses.ProjectLabelInfo(
                                        LABEL_ID, PROJECT_ID, LABEL_NAME, LABEL_COLOR,
                                        ADMIN_ID, Instant.now(), null
                                )
                        )
                )
        );

        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(PROJECT_ID, ADMIN_ID);

        // Act & Assert
        StepVerifier.create(labelService.listProjectLabels(REQUEST_ID, NODE_ID, listRequestDto))
                .expectNextMatches(response -> {
                    Assertions.assertThat(response.totalCount()).isEqualTo(1);
                    Assertions.assertThat(response.labels()).hasSize(1);
                    Assertions.assertThat(response.labels().get(0).name()).isEqualTo(LABEL_NAME);
                    return true;
                })
                .verifyComplete();

        verify(projectLabelsRepository).findByProjectIdAndDeletedAtIsNull(PROJECT_ID);
    }

    @Test
    @DisplayName("listProjectLabels: должен выбросить NOT_FOUND если меток нет")
    void shouldThrowNotFoundWhenNoLabels() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listProjectLabelRoles()).thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByProjectIdAndDeletedAtIsNull(any()))
                .thenReturn(Flux.empty());

        var listRequestDto = new LabelCommands.ListProjectLabelsRequestDto(PROJECT_ID, ADMIN_ID);

        // Act & Assert
        StepVerifier.create(labelService.listProjectLabels(REQUEST_ID, NODE_ID, listRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("No labels for project");
                })
                .verify();
    }

    // ===== REMOVE ISSUE LABEL TESTS =====

    @Test
    @DisplayName("removeIssueLabel: должен успешно удалить метку с задачи")
    void shouldRemoveLabelFromIssueSuccessfully() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.removeIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(issueLabelsRepository.existsByIssueIdAndLabelId(any(), any()))
                .thenReturn(Mono.just(true));

        Mockito.when(issueLabelsRepository.deleteByIssueIdAndLabelId(any(), any()))
                .thenReturn(Mono.empty());

        Mockito.when(payloadSerializer.createLabelRemovedPayload(any(ProjectLabels.class))).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        var removeRequestDto = new LabelCommands.RemoveIssueLabelRequestDto(ISSUE_ID, LABEL_ID, MEMBER_ID);

        // Act & Assert
        StepVerifier.create(labelService.removeIssueLabel(REQUEST_ID, NODE_ID, removeRequestDto))
                .expectNextMatches(response -> {
                    Assertions.assertThat(response.issueId()).isEqualTo(ISSUE_ID);
                    Assertions.assertThat(response.labelId()).isEqualTo(LABEL_ID);
                    return true;
                })
                .verifyComplete();

        Mockito.verify(issueLabelsRepository).deleteByIssueIdAndLabelId(ISSUE_ID, LABEL_ID);
        Mockito.verify(issueHistoryService).saveIssueHistory(eq(REQUEST_ID), eq(NODE_ID), eq(ISSUE_ID), eq(MEMBER_ID),
                eq(IssueEventType.LABEL_REMOVED), any(JsonNode.class));
        Mockito.verify(outboxEventService).saveOutboxEvent(eq(REQUEST_ID), eq(NODE_ID), eq(AggregateType.ISSUE),
                eq(ISSUE_ID), eq(EventType.ISSUE_LABEL_REMOVED), any(JsonNode.class));
    }

    @Test
    @DisplayName("removeIssueLabel: должен выбросить исключение если метка не привязана к задаче")
    void shouldThrowExceptionWhenLabelNotAttached() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.removeIssueLabelRoles()).thenReturn(Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(issueLabelsRepository.existsByIssueIdAndLabelId(any(), any()))
                .thenReturn(Mono.just(false));

        var removeRequestDto = new LabelCommands.RemoveIssueLabelRequestDto(ISSUE_ID, LABEL_ID, MEMBER_ID);

        // Act & Assert
        StepVerifier.create(labelService.removeIssueLabel(REQUEST_ID, NODE_ID, removeRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Label not attached to this issue");
                })
                .verify();

        Mockito.verify(issueLabelsRepository, never()).deleteByIssueIdAndLabelId(any(), any());
    }

    // ===== LIST ISSUE LABELS TESTS =====

    @Test
    @DisplayName("listIssueLabels: должен успешно вернуть метки задачи")
    void shouldReturnListOfIssueLabels() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listIssueLabelRoles()).thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(issueLabelsRepository.findLabelsByIssueId(any()))
                .thenReturn(Flux.just(projectLabel));

        Mockito.when(mapper.toListIssueLabelResponseDto(anyList())).thenReturn(
                LabelResponses.ListIssueLabelResponseDto.of(
                        java.util.List.of(
                                new LabelResponses.ProjectLabelInfo(
                                        LABEL_ID, PROJECT_ID, LABEL_NAME, LABEL_COLOR,
                                        ADMIN_ID, Instant.now(), null
                                )
                        )
                )
        );

        var listRequestDto = new LabelCommands.ListIssueLabelsRequestDto(ISSUE_ID, MEMBER_ID);

        // Act & Assert
        StepVerifier.create(labelService.listIssueLabels(REQUEST_ID, NODE_ID, listRequestDto))
                .expectNextMatches(response -> {
                    Assertions.assertThat(response.labels()).hasSize(1);
                    Assertions.assertThat(response.labels().get(0).name()).isEqualTo(LABEL_NAME);
                    return true;
                })
                .verifyComplete();

        Mockito.verify(issueLabelsRepository).findLabelsByIssueId(ISSUE_ID);
    }

    @Test
    @DisplayName("listIssueLabels: должен выбросить NOT_FOUND если у задачи нет меток")
    void shouldThrowNotFoundWhenIssueHasNoLabels() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.listIssueLabelRoles()).thenReturn(Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER, ProjectRole.ADMIN));

        var issue = mock(ru.taska.domain.Issue.class);
        Mockito.when(issue.getProjectId()).thenReturn(PROJECT_ID);

        Mockito.when(issueRepository.findActiveById(any())).thenReturn(Mono.just(issue));
        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(issueLabelsRepository.findLabelsByIssueId(any()))
                .thenReturn(Flux.empty());

        var listRequestDto = new LabelCommands.ListIssueLabelsRequestDto(ISSUE_ID, MEMBER_ID);

        // Act & Assert
        StepVerifier.create(labelService.listIssueLabels(REQUEST_ID, NODE_ID, listRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("No labels for issue");
                })
                .verify();
    }

    // ===== UPDATE PROJECT LABEL TESTS =====

    @Test
    @DisplayName("updateProjectLabel: должен успешно обновить метку")
    void shouldUpdateProjectLabelSuccessfully() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.updateProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(projectLabelsRepository.findActiveByName(any(), anyString()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.doAnswer(invocation -> {
            ProjectLabels label = invocation.getArgument(0);
            LabelCommands.UpdateProjectLabelRequestDto dto = invocation.getArgument(1);
            label.setName(dto.name().trim());
            label.setColor(dto.color().toUpperCase());
            return null;
        }).when(mapper).updateEntity(any(ProjectLabels.class), any(LabelCommands.UpdateProjectLabelRequestDto.class));

        Mockito.when(projectLabelsRepository.save(any(ProjectLabels.class))).thenReturn(Mono.just(projectLabel));
        Mockito.when(mapper.toProjectLabelInfo(any(ProjectLabels.class))).thenReturn(
                new LabelResponses.ProjectLabelInfo(LABEL_ID, PROJECT_ID, "UpdatedName", "#00FF00", ADMIN_ID, Instant.now(), null)
        );

        var updateRequestDto = new LabelCommands.UpdateProjectLabelRequestDto(
                LABEL_ID, PROJECT_ID, "UpdatedName", "#00FF00", ADMIN_ID
        );

        // Act & Assert
        StepVerifier.create(labelService.updateProjectLabel(REQUEST_ID, NODE_ID, updateRequestDto))
                .expectNextMatches(info -> {
                    Assertions.assertThat(info.name()).isEqualTo("UpdatedName");
                    Assertions.assertThat(info.color()).isEqualTo("#00FF00");
                    return true;
                })
                .verifyComplete();

        Mockito.verify(projectLabelsRepository).save(any(ProjectLabels.class));
    }

    @Test
    @DisplayName("updateProjectLabel: должен выбросить исключение если метка не найдена")
    void shouldThrowExceptionWhenLabelNotFoundForUpdate() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.updateProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.empty());

        var updateRequestDto = new LabelCommands.UpdateProjectLabelRequestDto(
                LABEL_ID, PROJECT_ID, "UpdatedName", "#00FF00", ADMIN_ID
        );

        // Act & Assert
        StepVerifier.create(labelService.updateProjectLabel(REQUEST_ID, NODE_ID, updateRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Label not found");
                })
                .verify();

        Mockito.verify(projectLabelsRepository, never()).save(any(ProjectLabels.class));
    }

    // ===== DELETE PROJECT LABEL TESTS =====

    @Test
    @DisplayName("deleteProjectLabel: должен успешно удалить метку")
    void shouldDeleteProjectLabelSuccessfully() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.deleteProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.just(projectLabel));

        Mockito.when(projectLabelsRepository.softDelete(any()))
                .thenReturn(Mono.empty());

        var deleteRequestDto = new LabelCommands.DeleteProjectLabelRequestDto(LABEL_ID, PROJECT_ID, ADMIN_ID);

        // Act & Assert
        StepVerifier.create(labelService.deleteProjectLabel(REQUEST_ID, NODE_ID, deleteRequestDto))
                .expectNextMatches(response -> {
                    Assertions.assertThat(response.labelId()).isEqualTo(LABEL_ID);
                    Assertions.assertThat(response.projectId()).isEqualTo(PROJECT_ID);
                    return true;
                })
                .verifyComplete();

        Mockito.verify(projectLabelsRepository).softDelete(LABEL_ID);
    }

    @Test
    @DisplayName("deleteProjectLabel: должен выбросить исключение если метка не найдена")
    void shouldThrowExceptionWhenLabelNotFoundForDelete() {
        // Arrange
        Mockito.when(issueProperties.allowedRoles()).thenReturn(allowedRoles);
        Mockito.when(allowedRoles.deleteProjectLabelRoles()).thenReturn(Set.of(ProjectRole.ADMIN));

        Mockito.when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(), any(), anySet()))
                .thenReturn(Mono.empty());

        Mockito.when(projectLabelsRepository.findByIdAndDeletedAtIsNull(any()))
                .thenReturn(Mono.empty());

        var deleteRequestDto = new LabelCommands.DeleteProjectLabelRequestDto(LABEL_ID, PROJECT_ID, ADMIN_ID);

        // Act & Assert
        StepVerifier.create(labelService.deleteProjectLabel(REQUEST_ID, NODE_ID, deleteRequestDto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Label not found");
                })
                .verify();

        Mockito.verify(projectLabelsRepository, never()).softDelete(any());
    }
}