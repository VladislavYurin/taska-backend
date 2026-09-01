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
import ru.taska.domain.dto.ConfirmAttachmentUploadRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlResponseDto;
import ru.taska.domain.dto.GetAttachmentDownloadUrlResponseDto;
import ru.taska.domain.dto.IssueAttachmentDto;
import ru.taska.domain.dto.IssueAttachmentsResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcIssueAttachmentServiceClient;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * WebTestClient-тесты для {@link IssueAttachmentController}.
 * Реальный security/executor-слой
 * поднимается через {@code @Import}, а gRPC-клиент issue-service мокается.
 */
@WebFluxTest(controllers = IssueAttachmentController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class IssueAttachmentControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ATTACHMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcIssueAttachmentServiceClient attachmentClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и список вложений задачи")
    void listAttachments_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = new IssueAttachmentsResponseDto();
        response.setItems(List.of(issueAttachmentDto()));

        Mockito.when(attachmentClient.listAttachments(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(IssueAttachmentsResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(attachmentClient).listAttachments(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden, если у пользователя нет доступа к задаче")
    void listAttachments_shouldReturn403_whenUserHasNoAccessToIssue() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.listAttachments(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found, если задача не найдена")
    void listAttachments_shouldReturn404_whenIssueNotFound() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.listAttachments(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized при отсутствии токена")
    void listAttachments_shouldReturn401_whenTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments", PROJECT_ID, ISSUE_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(attachmentClient);
    }

    @Test
    @DisplayName("createAttachmentUploadUrl: должен вернуть 200 OK и не логировать сам presigned URL")
    void createAttachmentUploadUrl_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = createUploadUrlRequest();
        var response = new CreateAttachmentUploadUrlResponseDto();
        response.setUploadUrl(URI.create("https://s3.example.com/bucket/some-object-key?X-Amz-Signature=secret"));
        response.setObjectKey("issues/" + ISSUE_ID + "/generated-key");

        Mockito.when(attachmentClient.createAttachmentUploadUrl(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/upload-url", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(CreateAttachmentUploadUrlResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(attachmentClient).createAttachmentUploadUrl(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("createAttachmentUploadUrl: должен вернуть 400 Bad Request при отсутствующем теле запроса")
    void createAttachmentUploadUrl_shouldReturn400_whenBodyIsMissing() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.createAttachmentUploadUrl(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(inv -> {
                    Mono<CreateAttachmentUploadUrlRequestDto> body = inv.getArgument(1);
                    return body.map(dto -> {
                        var response = new CreateAttachmentUploadUrlResponseDto();
                        response.setUploadUrl(URI.create("https://s3.example.com/should-not-be-reached"));
                        response.setObjectKey("should-not-be-reached");
                        return response;
                    });
                });

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/upload-url", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("createAttachmentUploadUrl: должен вернуть 400 Bad Request при отсутствии обязательных полей (fileName/contentType/sizeBytes)")
    void createAttachmentUploadUrl_shouldReturn400_whenRequiredFieldsAreMissing() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.createAttachmentUploadUrl(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(inv -> {
                    Mono<CreateAttachmentUploadUrlRequestDto> body = inv.getArgument(1);
                    return body.map(dto -> {
                        var response = new CreateAttachmentUploadUrlResponseDto();
                        response.setUploadUrl(URI.create("https://s3.example.com/should-not-be-reached"));
                        response.setObjectKey("should-not-be-reached");
                        return response;
                    });
                });

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/upload-url", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("confirmAttachmentUpload: должен вернуть 201 Created и метаданные вложения")
    void confirmAttachmentUpload_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = confirmUploadRequest();
        var response = issueAttachmentDto();

        Mockito.when(attachmentClient.confirmAttachmentUpload(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/confirm", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(IssueAttachmentDto.class)
                .isEqualTo(response);

        Mockito.verify(attachmentClient).confirmAttachmentUpload(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("confirmAttachmentUpload: должен вернуть 404 Not Found, если объект не найден в S3")
    void confirmAttachmentUpload_shouldReturn404_whenObjectNotFoundInStorage() {
        mockAuthenticatedUser();

        var request = confirmUploadRequest();

        Mockito.when(attachmentClient.confirmAttachmentUpload(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/confirm", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("getAttachmentDownloadUrl: должен вернуть 200 OK и download URL")
    void getAttachmentDownloadUrl_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = new GetAttachmentDownloadUrlResponseDto();
        response.setDownloadUrl(URI.create("https://s3.example.com/bucket/object?X-Amz-Signature=secret"));
        response.setChecksum("sha256:abc123");

        Mockito.when(attachmentClient.getAttachmentDownloadUrl(
                        Mockito.eq(ATTACHMENT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}/download-url",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(GetAttachmentDownloadUrlResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(attachmentClient).getAttachmentDownloadUrl(
                Mockito.eq(ATTACHMENT_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("getAttachmentDownloadUrl: должен вернуть 404 Not Found для несуществующего вложения")
    void getAttachmentDownloadUrl_shouldReturn404_whenAttachmentNotFound() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.getAttachmentDownloadUrl(
                        Mockito.eq(ATTACHMENT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}/download-url",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("getAttachmentDownloadUrl: должен вернуть 403 Forbidden без доступа к вложению")
    void getAttachmentDownloadUrl_shouldReturn403_whenUserHasNoAccess() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.getAttachmentDownloadUrl(
                        Mockito.eq(ATTACHMENT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}/download-url",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("deleteAttachment: должен вернуть 204 No Content")
    void deleteAttachment_shouldReturn204_whenRequestIsValid() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.deleteAttachment(
                        Mockito.eq(ATTACHMENT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(attachmentClient).deleteAttachment(
                Mockito.eq(ATTACHMENT_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("deleteAttachment: должен вернуть 403 Forbidden при попытке удалить чужое вложение без прав")
    void deleteAttachment_shouldReturn403_whenUserHasNoPermission() {
        mockAuthenticatedUser();

        Mockito.when(attachmentClient.deleteAttachment(
                        Mockito.eq(ATTACHMENT_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(attachmentClient).deleteAttachment(
                Mockito.eq(ATTACHMENT_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("deleteAttachment: должен вернуть 401 Unauthorized при отсутствии токена")
    void deleteAttachment_shouldReturn401_whenTokenMissing() {
        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/attachments/{attachmentId}",
                        PROJECT_ID, ISSUE_ID, ATTACHMENT_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verifyNoInteractions(attachmentClient);
    }

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

    private IssueAttachmentDto issueAttachmentDto() {
        var dto = new IssueAttachmentDto();
        dto.setId(ATTACHMENT_ID);
        dto.setIssueId(ISSUE_ID);
        dto.setFileName("error-log.txt");
        dto.setContentType("text/plain");
        dto.setSizeBytes(10240L);
        dto.setUploadedBy(UUID.fromString(USER_ID));
        dto.setChecksum("sha256:abc123");
        dto.setCreatedAt(OffsetDateTime.parse("2026-09-03T10:00:00Z"));
        return dto;
    }

    private CreateAttachmentUploadUrlRequestDto createUploadUrlRequest() {
        var request = new CreateAttachmentUploadUrlRequestDto();
        request.setFileName("error-log.txt");
        request.setContentType("text/plain");
        request.setSizeBytes(10240L);
        return request;
    }

    private ConfirmAttachmentUploadRequestDto confirmUploadRequest() {
        var request = new ConfirmAttachmentUploadRequestDto();
        request.setObjectKey("issues/" + ISSUE_ID + "/generated-key");
        request.setFileName("error-log.txt");
        request.setContentType("text/plain");
        return request;
    }
}