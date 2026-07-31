package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.AddProjectMemberRequestDto;
import ru.taska.domain.dto.ChangeProjectMemberRoleRequestDto;
import ru.taska.domain.dto.CreateProjectRequestDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcProjectServiceClient;

@WebFluxTest(controllers = ProjectController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
public class ProjectControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "d221b01d-9c5b-4c3b-b3be-b5502f9d1a12";
    private static final String PROJECT_ID = "6d774efa-57d8-4ae0-a27e-2984d1dfbbf6";
    private static final String MEMBER_ID = "7adceb90-d1ea-4e32-bda7-9c9bd8aa8ef5";
    private static final String PROJECT_KEY = "TASKA";
    private static final String PROJECT_NAME = "Taska Platform";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcProjectServiceClient projectClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ========== CREATE PROJECT ==========

    @Test
    @DisplayName("Должен успешно создать проект и вернуть 201 Created")
    void createProject_shouldReturn201Created() {
        // given
        mockAuthenticatedUser();

        var request = new CreateProjectRequestDto();
        request.setProjectKey(PROJECT_KEY);
        request.setName(PROJECT_NAME);

        var response = new ProjectResponseDto();
        response.setId(PROJECT_ID);
        response.setProjectKey(PROJECT_KEY);
        response.setName(PROJECT_NAME);

        Mockito.when(projectClient.createProject(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ProjectResponseDto.class).isEqualTo(response);

        Mockito.verify(projectClient).createProject(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при пустом projectKey")
    void createProject_shouldReturn400_whenProjectKeyIsEmpty() {
        // given
        mockAuthenticatedUser();

        var request = new CreateProjectRequestDto();
        request.setProjectKey("");
        request.setName(PROJECT_NAME);

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(projectClient, Mockito.never()).createProject(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при пустом name")
    void createProject_shouldReturn400_whenNameIsEmpty() {
        // given
        mockAuthenticatedUser();

        var request = new CreateProjectRequestDto();
            request.setProjectKey(PROJECT_KEY);
            request.setName(null);

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 409 Conflict при существующем projectKey")
    void createProject_shouldReturn409_whenProjectKeyExists() {
        // given
        mockAuthenticatedUser();

        var request = new CreateProjectRequestDto();
                request.setProjectKey(PROJECT_KEY);
                request.setName(PROJECT_NAME);

        Mockito.when(projectClient.createProject(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Project key already exists")));

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== LIST PROJECTS ==========

    @Test
    @DisplayName("Должен вернуть список проектов с кодом 200 OK")
    void listMyProjects_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var response = new ListMyProjectResponseDto();
        response.setItems(java.util.Collections.emptyList());

        Mockito.when(projectClient.listMyProjects(Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ListMyProjectResponseDto.class).isEqualTo(response);

        Mockito.verify(projectClient).listMyProjects(Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть пустой список с кодом 200 OK")
    void listMyProjects_shouldReturnEmptyList() {
        // given
        mockAuthenticatedUser();

        var response = new ListMyProjectResponseDto();
        response.setItems(java.util.Collections.emptyList());

        Mockito.when(projectClient.listMyProjects(Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isEmpty();
    }

    // ========== GET PROJECT ==========

    @Test
    @DisplayName("Должен вернуть проект с кодом 200 OK")
    void getProject_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var response = new ProjectResponseDto();
        response.setId(PROJECT_ID);
        response.setProjectKey(PROJECT_KEY);
        response.setName(PROJECT_NAME);

        Mockito.when(projectClient.getProject(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(ProjectResponseDto.class).isEqualTo(response);

        Mockito.verify(projectClient).getProject(Mockito.eq(PROJECT_ID), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при отсутствии доступа к проекту")
    void getProject_shouldReturn403_whenAccessDenied() {
        // given
        mockAuthenticatedUser();

        Mockito.when(projectClient.getProject(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied")));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== ADD MEMBER ==========

    @Test
    @DisplayName("Должен успешно добавить участника с кодом 201 Created")
    void addProjectMember_shouldReturn201Created() {
        // given
        mockAuthenticatedUser();

        var request = new AddProjectMemberRequestDto();
        request.setUserId(MEMBER_ID);
        request.setRole(AddProjectMemberRequestDto.RoleEnum.MEMBER);

        var response = new ProjectMemberResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setUserId(MEMBER_ID);
        response.setRole("MEMBER");

        Mockito.when(projectClient.addProjectMember(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/members", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectBody(ProjectMemberResponseDto.class).isEqualTo(response);

        Mockito.verify(projectClient).addProjectMember(Mockito.eq(PROJECT_ID), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 409 Conflict при повторном добавлении участника")
    void addProjectMember_shouldReturn409_whenDuplicateMember() {
        // given
        mockAuthenticatedUser();

        var request = new AddProjectMemberRequestDto();
        request.setUserId(MEMBER_ID);
        request.setRole(AddProjectMemberRequestDto.RoleEnum.MEMBER);

        Mockito.when(projectClient.addProjectMember(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "User already a member")));

        // when & then
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/members", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id");
    }

    // ========== CHANGE MEMBER ROLE ==========

    @Test
    @DisplayName("Должен успешно изменить роль с кодом 200 OK")
    void changeProjectMemberRole_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var request = new ChangeProjectMemberRoleRequestDto();
        request.setRole(ChangeProjectMemberRoleRequestDto.RoleEnum.VIEWER);

        var response = new ProjectMemberResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setUserId(MEMBER_ID);
        response.setRole("VIEWER");

        Mockito.when(projectClient.changeProjectMemberRole(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.patch()
                .uri("/api/v1/projects/{projectId}/members/{userId}", PROJECT_ID, MEMBER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(ProjectMemberResponseDto.class).isEqualTo(response);

        Mockito.verify(projectClient).changeProjectMemberRole(Mockito.eq(PROJECT_ID), Mockito.eq(MEMBER_ID), Mockito.any(), Mockito.any());
    }

    // ========== REMOVE MEMBER ==========

    @Test
    @DisplayName("Должен успешно удалить участника с кодом 204 No Content")
    void removeProjectMember_shouldReturn204NoContent() {
        // given
        mockAuthenticatedUser();

        Mockito.when(projectClient.removeProjectMember(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        // when & then
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/members/{userId}", PROJECT_ID, MEMBER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(projectClient).removeProjectMember(Mockito.eq(PROJECT_ID), Mockito.eq(MEMBER_ID), Mockito.any());
    }

    // ========== ADMIN RULES ==========

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при попытке удалить последнего ADMIN")
    void removeLastAdmin_shouldReturn400() {
        // given
        mockAuthenticatedUser();

        // Мокаем ошибку от project-service: нельзя удалить последнего админа
        Mockito.when(projectClient.removeProjectMember(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot remove the last admin from the project"
                )));

        // when & then
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/members/{userId}", PROJECT_ID, MEMBER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists()
                .jsonPath("$.message").isEqualTo("Cannot remove the last admin from the project");

        Mockito.verify(projectClient).removeProjectMember(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(MEMBER_ID),
                Mockito.any()
        );
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при попытке понизить последнего ADMIN")
    void changeLastAdminRole_shouldReturn400() {
        // given
        mockAuthenticatedUser();

        var request = new ChangeProjectMemberRoleRequestDto();
        request.setRole(ChangeProjectMemberRoleRequestDto.RoleEnum.MEMBER);

        Mockito.when(projectClient.changeProjectMemberRole(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any())
                )
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Can't modify last admin: " + MEMBER_ID + " in project: " + PROJECT_ID))
                );

        // when & then
        webTestClient.patch()
                .uri("/api/v1/projects/{projectId}/members/{userId}", PROJECT_ID, MEMBER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists()
                .jsonPath("$.message").isEqualTo("Can't modify last admin: " + MEMBER_ID + " in project: " + PROJECT_ID);

        Mockito.verify(projectClient).changeProjectMemberRole(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(MEMBER_ID),
                Mockito.any(),
                Mockito.any()
        );
    }

    // ========== AUTHENTICATION ==========

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized без Bearer токена")
    void shouldReturn401_whenNoBearerToken() {
        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(projectClient, Mockito.never()).listMyProjects(Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть корректный X-Request-Id в ответе")
    void shouldReturnXRequestIdInResponse() {
        // given
        mockAuthenticatedUser();

        var response = new ListMyProjectResponseDto();
        response.setItems(java.util.Collections.emptyList());

        Mockito.when(projectClient.listMyProjects(Mockito.any()))
                .thenReturn(Mono.just(response));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().valueMatches("X-Request-Id", ".+");
    }

    // ========== DOWNSTREAM ERRORS ==========

    @Test
    @DisplayName("Должен вернуть 503 Service Unavailable при недоступном downstream")
    void shouldReturn503_whenDownstreamUnavailable() {
        // given
        mockAuthenticatedUser();

        Mockito.when(projectClient.listMyProjects(Mockito.any()))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout при превышении deadline")
    void shouldReturn504_whenDeadlineExceeded() {
        // given
        mockAuthenticatedUser();

        Mockito.when(projectClient.listMyProjects(Mockito.any()))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout Exceeded").asRuntimeException()));

        // when & then
        webTestClient.get()
                .uri("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id");
    }

    // ========== HELPER METHODS ==========

    private void mockAuthenticatedUser() {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(
                        UserContext.newBuilder()
                                .setUserId(USER_ID)
                                .build()
                )
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .login("test-login")
                .email("test@example.com")
                .displayName("Test User")
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);

    }
}
