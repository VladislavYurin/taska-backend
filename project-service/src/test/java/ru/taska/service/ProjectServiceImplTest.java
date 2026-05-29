package ru.taska.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.entity.*;
import ru.taska.exception.ProjectAlreadyExistsException;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;
import ru.taska.repository.ProjectSettingRepository;
import ru.taska.service.impl.ProjectServiceImpl;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectMemberRepository projectMemberRepository;
    @Mock
    private ProjectSettingRepository projectSettingRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProjectServiceImpl projectService;

    private UUID userId;
    private UUID projectId;
    private Project mockProject;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        mockProject = Project.builder()
                .id(projectId)
                .projectKey("PRJ")
                .name("Test Project")
                .createdBy(userId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Успешное создание проекта со всеми сопутствующими сущностями и Outbox-ивентом")
    void createProject_Success() {
        // Given
        String projectKey = "PRJ";
        String name = "Test Project";
        String userIdStr = userId.toString();

        Mockito.when(projectRepository.findByProjectKey(projectKey)).thenReturn(Mono.empty());
        Mockito.when(projectRepository.save(Mockito.any(Project.class))).thenReturn(Mono.just(mockProject));

        Mockito.when(projectMemberRepository.save(Mockito.any(ProjectMember.class))).thenReturn(Mono.just(new ProjectMember()));
        Mockito.when(projectSettingRepository.save(Mockito.any(ProjectSetting.class))).thenReturn(Mono.just(new ProjectSetting()));
        Mockito.when(outboxEventRepository.save(Mockito.any(OutboxEvent.class))).thenReturn(Mono.just(new OutboxEvent()));

        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        ArgumentCaptor<ProjectSetting> settingCaptor = ArgumentCaptor.forClass(ProjectSetting.class);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);

        // When
        Mono<Project> resultMono = projectService.createProject(projectKey, name, userIdStr);

        // Then
        StepVerifier.create(resultMono)
                .expectNextMatches(project -> {
                    Assertions.assertThat(project.getId()).isEqualTo(projectId);
                    Assertions.assertThat(project.getProjectKey()).isEqualTo("PRJ");
                    return true;
                })
                .verifyComplete();

        Mockito.verify(projectMemberRepository).save(memberCaptor.capture());
        Mockito.verify(projectSettingRepository).save(settingCaptor.capture());
        Mockito.verify(outboxEventRepository).save(outboxCaptor.capture());

        // 1. Проверяем мембера
        ProjectMember savedMember = memberCaptor.getValue();
        Assertions.assertThat(savedMember.getProjectId()).isEqualTo(projectId);
        Assertions.assertThat(savedMember.getUserId()).isEqualTo(userId);
        Assertions.assertThat(savedMember.getRole()).isEqualTo(ProjectRole.ADMIN);

        // 2. Проверяем дефолтные настройки
        ProjectSetting savedSetting = settingCaptor.getValue();
        Assertions.assertThat(savedSetting.getProjectId()).isEqualTo(projectId);
        Assertions.assertThat(savedSetting.getSettings().isEmpty()).isTrue();

        // 3. Проверяем структуру OutboxEvent
        OutboxEvent savedEvent = outboxCaptor.getValue();
        Assertions.assertThat(savedEvent.getAggregateType()).isEqualTo("PROJECT");
        Assertions.assertThat(savedEvent.getAggregateId()).isEqualTo(projectId);
        Assertions.assertThat(savedEvent.getEventType()).isEqualTo("PROJECT_CREATED");
        Assertions.assertThat(savedEvent.getAttempts()).isEqualTo(0);
        Assertions.assertThat(savedEvent.getPayload().get("projectKey").asText()).isEqualTo("PRJ");
    }

    @Test
    @DisplayName("Ошибка создания: Проект с таким ключом уже существует")
    void createProject_AlreadyExists() {
        // Given
        // doReturn принимает заглушку, а в when передается сам мок-репозиторий
        Mockito.doReturn(Mono.just(mockProject))
                .when(projectRepository)
                .findByProjectKey("PRJ");

        // When
        Mono<Project> resultMono = projectService.createProject("PRJ", "New Name", userId.toString());

        // Then
        StepVerifier.create(resultMono)
                .expectError(ProjectAlreadyExistsException.class)
                .verify();

        Mockito.verify(projectRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(outboxEventRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("Ошибка валидации: Некорректный формат UUID пользователя")
    void createProject_InvalidUuid() {
        // When
        Mono<Project> resultMono = projectService.createProject("PRJ", "Name", "not-a-valid-uuid");

        // Then
        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                    StatusRuntimeException ex = (StatusRuntimeException) throwable;
                    Assertions.assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getStatus().getDescription()).contains("Invalid UserId UUID format");
                    return true;
                })
                .verify();

        Mockito.verifyNoInteractions(projectRepository, outboxEventRepository);
    }
}