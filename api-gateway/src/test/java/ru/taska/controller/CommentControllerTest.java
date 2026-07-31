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
import ru.taska.domain.dto.CommentRequestDto;
import ru.taska.domain.dto.CommentResponseDto;
import ru.taska.domain.dto.CommentsListResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.CommentRestMapper;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcCommentServiceClient;

import java.time.OffsetDateTime;
import java.util.UUID;

@WebFluxTest(controllers = CommentController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class,
        CommentRestMapper.class
})
class CommentControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "d221b01d-9c5b-4c3b-b3be-b5502f9d1a12";
    private static final String PROJECT_ID = "6d774efa-57d8-4ae0-a27e-2984d1dfbbf6";
    private static final String ISSUE_ID = "7adceb90-d1ea-4e32-bda7-9c9bd8aa8ef5";
    private static final String COMMENT_ID = "8bdf790d-866e-428c-bf4f-dafb2ef61cf8";
    private static final String COMMENT_BODY = "Test comment body";
    private static final String UPDATED_COMMENT_BODY = "Updated comment body";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcCommentServiceClient commentClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ========== LIST COMMENTS ==========

    @Test
    @DisplayName("Должен вернуть список комментариев с кодом 200 OK")
    void listComments_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var commentResponse = new CommentResponseDto();
        commentResponse.setId(UUID.fromString(COMMENT_ID));
        commentResponse.setIssueId(UUID.fromString(ISSUE_ID));
        commentResponse.setProjectId(UUID.fromString(PROJECT_ID));
        commentResponse.setAuthorUserId(UUID.fromString(USER_ID));
        commentResponse.setBody(COMMENT_BODY);
        commentResponse.setCreatedAt(OffsetDateTime.now());
        commentResponse.setUpdatedAt(null);
        commentResponse.setVersion(1);

        var response = new CommentsListResponseDto();
        response.setItems(java.util.List.of(commentResponse));
        response.setTotalCount(1);

        Mockito.when(commentClient.listComments(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        )).thenReturn(Mono.just(response));

        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments?page=0&pageSize=20",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.totalCount").isNumber();
    }

    @Test
    @DisplayName("Должен вернуть пустой список комментариев с кодом 200 OK")
    void listComments_shouldReturnEmptyList() {
        // given
        mockAuthenticatedUser();

        var response = new CommentsListResponseDto();
        response.setItems(java.util.Collections.emptyList());
        response.setTotalCount(0);

        Mockito.when(commentClient.listComments(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        )).thenReturn(Mono.just(response));

        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments?page=0&pageSize=20",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isEmpty()
                .jsonPath("$.totalCount").isEqualTo(0);
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized при отсутствии токена")
    void listComments_shouldReturn401_whenNoToken() {
        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== ADD COMMENT ==========

    @Test
    @DisplayName("Должен успешно добавить комментарий с кодом 200 OK")
    void addComment_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(COMMENT_BODY);

        var response = new CommentResponseDto();
        response.setId(UUID.fromString(COMMENT_ID));
        response.setIssueId(UUID.fromString(ISSUE_ID));
        response.setProjectId(UUID.fromString(PROJECT_ID));
        response.setAuthorUserId(UUID.fromString(USER_ID));
        response.setBody(COMMENT_BODY);
        response.setCreatedAt(OffsetDateTime.now());
        response.setUpdatedAt(null);
        response.setVersion(1);

        Mockito.when(commentClient.addComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.just(response));

        // Act & Assert
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.body").isEqualTo(COMMENT_BODY);
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при пустом теле комментария")
    void addComment_shouldReturn400_whenBodyIsEmpty() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody("");

        // Act & Assert
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при отсутствии прав на добавление комментария")
    void addComment_shouldReturn403_whenPermissionDenied() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(COMMENT_BODY);

        Mockito.when(commentClient.addComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied")));

        // Act & Assert
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found при несуществующей задаче")
    void addComment_shouldReturn404_whenIssueNotFound() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(COMMENT_BODY);

        Mockito.when(commentClient.addComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found")));

        // Act & Assert
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== UPDATE COMMENT ==========

    @Test
    @DisplayName("Должен успешно обновить комментарий с кодом 200 OK")
    void updateComment_shouldReturn200OK() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(UPDATED_COMMENT_BODY);

        var response = new CommentResponseDto();
        response.setId(UUID.fromString(COMMENT_ID));
        response.setIssueId(UUID.fromString(ISSUE_ID));
        response.setProjectId(UUID.fromString(PROJECT_ID));
        response.setAuthorUserId(UUID.fromString(USER_ID));
        response.setBody(UPDATED_COMMENT_BODY);
        response.setCreatedAt(OffsetDateTime.now());
        response.setUpdatedAt(OffsetDateTime.now());
        response.setVersion(2);

        Mockito.when(commentClient.updateComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.just(response));

        // Act & Assert
        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.body").isEqualTo(UPDATED_COMMENT_BODY)
                .jsonPath("$.version").isEqualTo(2);
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при попытке обновить чужой комментарий")
    void updateComment_shouldReturn403_whenNotAuthor() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(UPDATED_COMMENT_BODY);

        Mockito.when(commentClient.updateComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can modify this comment")));

        // Act & Assert
        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 409 Conflict при конфликте версий")
    void updateComment_shouldReturn409_whenVersionConflict() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(UPDATED_COMMENT_BODY);

        Mockito.when(commentClient.updateComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Comment was modified by another user. Please refresh and try again."
        )));

        // Act & Assert
        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
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

    @Test
    @DisplayName("Должен вернуть 404 Not Found при обновлении несуществующего комментария")
    void updateComment_shouldReturn404_whenCommentNotFound() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(UPDATED_COMMENT_BODY);

        Mockito.when(commentClient.updateComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.anyString(),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found")));

        // Act & Assert
        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== DELETE COMMENT ==========

    @Test
    @DisplayName("Должен успешно удалить комментарий с кодом 204 No Content")
    void deleteComment_shouldReturn204NoContent() {
        // given
        mockAuthenticatedUser();

        Mockito.when(commentClient.deleteComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any()
        )).thenReturn(Mono.empty());

        // Act & Assert
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()  // ← изменено с isOk() на isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при попытке удалить чужой комментарий")
    void deleteComment_shouldReturn403_whenNotAuthor() {
        // given
        mockAuthenticatedUser();

        Mockito.when(commentClient.deleteComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can delete the comment")));

        // Act & Assert
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found при удалении несуществующего комментария")
    void deleteComment_shouldReturn404_whenCommentNotFound() {
        // given
        mockAuthenticatedUser();

        Mockito.when(commentClient.deleteComment(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any()
        )).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found")));

        // Act & Assert
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    // ========== VALIDATION ==========

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при пустом комментарии при создании")
    void addComment_shouldReturn400_whenBodyIsNull() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody(null);

        // Act & Assert
        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при пустом комментарии при обновлении")
    void updateComment_shouldReturn400_whenBodyIsEmpty() {
        // given
        mockAuthenticatedUser();

        var request = new CommentRequestDto();
        request.setBody("");

        // Act & Assert
        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments/{commentId}",
                        PROJECT_ID, ISSUE_ID, COMMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    // ========== DOWNSTREAM ERRORS ==========

    @Test
    @DisplayName("Должен вернуть 503 Service Unavailable при недоступном downstream")
    void listComments_shouldReturn503_whenDownstreamUnavailable() {
        // given
        mockAuthenticatedUser();

        Mockito.when(commentClient.listComments(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        )).thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout при превышении deadline")
    void listComments_shouldReturn504_whenDeadlineExceeded() {
        // given
        mockAuthenticatedUser();

        Mockito.when(commentClient.listComments(
                Mockito.any(UUID.class),
                Mockito.any(UUID.class),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        )).thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout Exceeded").asRuntimeException()));

        // Act & Assert
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/comments",
                        PROJECT_ID, ISSUE_ID)
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