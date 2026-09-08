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
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.*;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcLabelServiceClient;

import java.util.UUID;

@WebFluxTest(controllers = LabelsController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class LabelsControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID LABEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String LABEL_NAME = "backend";
    private static final String LABEL_COLOR = "#0052CC";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcLabelServiceClient labelClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ===== Project Labels Tests =====

    @Test
    @DisplayName("Должен вернуть 200 OK и список меток проекта")
    void listProjectLabels_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var labelResponse = new ProjectLabelResponseDto();
        labelResponse.setId(LABEL_ID);
        labelResponse.setProjectId(PROJECT_ID);
        labelResponse.setName(LABEL_NAME);
        labelResponse.setColor(LABEL_COLOR);

        var response = new ListProjectLabelsResponseDto();
        response.setItems(java.util.List.of(labelResponse));
        response.setTotalCount(1);

        Mockito.when(labelClient.listProjectLabels(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/labels", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ListProjectLabelsResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(labelClient).listProjectLabels(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 201 Created при создании метки проекта")
    void createProjectLabel_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = createProjectLabelRequest(LABEL_NAME, LABEL_COLOR);
        var response = createProjectLabelResponse(LABEL_ID, PROJECT_ID, LABEL_NAME, LABEL_COLOR, UUID.fromString(USER_ID));

        Mockito.when(labelClient.createProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/labels", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ProjectLabelResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(labelClient).createProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 409 Conflict при создании дубликата метки")
    void createProjectLabel_shouldReturn409_whenLabelNameAlreadyExists() {
        mockAuthenticatedUser();

        var request = createProjectLabelRequest(LABEL_NAME, LABEL_COLOR);

        Mockito.when(labelClient.createProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.ALREADY_EXISTS.asRuntimeException()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/labels", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).createProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK при обновлении метки проекта")
    void updateProjectLabel_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = updateProjectLabelRequest("updated-name", "#FF0000");
        var response = createProjectLabelResponse(LABEL_ID, PROJECT_ID, "updated-name", "#FF0000", UUID.fromString(USER_ID));

        Mockito.when(labelClient.updateProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.patch()
                .uri("/api/v1/projects/{projectId}/labels/{labelId}", PROJECT_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ProjectLabelResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(labelClient).updateProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found при обновлении несуществующей метки")
    void updateProjectLabel_shouldReturn404_whenLabelNotFound() {
        mockAuthenticatedUser();

        var request = updateProjectLabelRequest("updated-name", "#FF0000");

        Mockito.when(labelClient.updateProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.patch()
                .uri("/api/v1/projects/{projectId}/labels/{labelId}", PROJECT_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).updateProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 204 No Content при удалении метки проекта")
    void deleteProjectLabel_shouldReturn204_whenRequestIsValid() {
        mockAuthenticatedUser();

        Mockito.when(labelClient.deleteProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/labels/{labelId}", PROJECT_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(labelClient).deleteProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при удалении метки проекта MEMBER'ом (только ADMIN)")
    void deleteProjectLabel_shouldReturn403_whenMemberHasNoPermission() {
        mockAuthenticatedUserWithRole(GlobalRole.USER);

        Mockito.when(labelClient.deleteProjectLabel(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/labels/{labelId}", PROJECT_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).deleteProjectLabel(
                Mockito.eq(PROJECT_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized при отсутствии токена")
    void listProjectLabels_shouldReturn401_whenTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/labels", PROJECT_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(labelClient);
    }

    // ===== Issue Labels Tests =====

    @Test
    @DisplayName("Должен вернуть 200 OK и список меток задачи")
    void listIssueLabels_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var labelResponse = createIssueLabelResponse(LABEL_ID, LABEL_NAME, LABEL_COLOR);
        var response = new ListIssueLabelsResponseDto();
        response.setItems(java.util.List.of(labelResponse));
        response.setTotalCount(1);

        Mockito.when(labelClient.listIssueLabels(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ListIssueLabelsResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(labelClient).listIssueLabels(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 201 Created при добавлении метки к задаче")
    void addIssueLabel_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = addIssueLabelRequest(LABEL_ID);
        var response = addIssueLabelResponse(ISSUE_ID, LABEL_ID, UUID.fromString(USER_ID));

        Mockito.when(labelClient.addIssueLabel(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(AddIssueLabelResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(labelClient).addIssueLabel(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 409 Conflict при повторном добавлении метки к задаче")
    void addIssueLabel_shouldReturn409_whenLabelAlreadyAdded() {
        mockAuthenticatedUser();

        var request = addIssueLabelRequest(LABEL_ID);

        Mockito.when(labelClient.addIssueLabel(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.ALREADY_EXISTS.asRuntimeException()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).addIssueLabel(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found при добавлении несуществующей метки к задаче")
    void addIssueLabel_shouldReturn404_whenLabelNotFound() {
        mockAuthenticatedUser();

        var request = addIssueLabelRequest(LABEL_ID);

        Mockito.when(labelClient.addIssueLabel(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).addIssueLabel(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 204 No Content при удалении метки у задачи")
    void removeIssueLabel_shouldReturn204_whenRequestIsValid() {
        mockAuthenticatedUser();

        Mockito.when(labelClient.removeIssueLabel(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels/{labelId}", PROJECT_ID, ISSUE_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(labelClient).removeIssueLabel(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found при удалении несуществующей метки у задачи")
    void removeIssueLabel_shouldReturn404_whenLabelNotAttached() {
        mockAuthenticatedUser();

        Mockito.when(labelClient.removeIssueLabel(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(LABEL_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/labels/{labelId}", PROJECT_ID, ISSUE_ID, LABEL_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(labelClient).removeIssueLabel(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.eq(LABEL_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    // ===== Helper Methods =====

    private void mockAuthenticatedUser() {
        mockAuthenticatedUserWithRole(GlobalRole.USER);
    }

    private void mockAuthenticatedUserWithRole(GlobalRole role) {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(USER_ID).build())
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(role)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }

    // ===== Factory Methods for Requests =====

    private CreateProjectLabelRequestDto createProjectLabelRequest(String name, String color) {
        var request = new CreateProjectLabelRequestDto();
        request.setName(name);
        request.setColor(color);
        return request;
    }

    private UpdateProjectLabelRequestDto updateProjectLabelRequest(String name, String color) {
        var request = new UpdateProjectLabelRequestDto();
        request.setName(name);
        request.setColor(color);
        return request;
    }

    private AddIssueLabelRequestDto addIssueLabelRequest(UUID labelId) {
        var request = new AddIssueLabelRequestDto();
        request.setLabelId(labelId);
        return request;
    }

    // ===== Factory Methods for Responses =====

    private ProjectLabelResponseDto createProjectLabelResponse(UUID id, UUID projectId, String name, String color, UUID createdBy) {
        var response = new ProjectLabelResponseDto();
        response.setId(id);
        response.setProjectId(projectId);
        response.setName(name);
        response.setColor(color);
        response.setCreatedBy(createdBy);
        return response;
    }

    private IssueLabelResponseDto createIssueLabelResponse(UUID id, String name, String color) {
        var response = new IssueLabelResponseDto();
        response.setId(id);
        response.setName(name);
        response.setColor(color);
        return response;
    }

    private AddIssueLabelResponseDto addIssueLabelResponse(UUID issueId, UUID labelId, UUID createdBy) {
        var response = new AddIssueLabelResponseDto();
        response.setIssueId(issueId);
        response.setLabelId(labelId);
        response.setCreatedBy(createdBy);
        return response;
    }
}