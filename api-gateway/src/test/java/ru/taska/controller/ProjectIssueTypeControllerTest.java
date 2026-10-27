package ru.taska.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.grpc.Status;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.*;
import ru.taska.domain.dto.*;
import ru.taska.error.*;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcProjectIssueTypeClient;

@WebFluxTest(controllers = ProjectIssueTypeController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class})
class ProjectIssueTypeControllerTest {
    @Autowired WebTestClient http;
    @MockitoBean GrpcProjectIssueTypeClient client;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;
    private final UUID project = UUID.randomUUID(), user = UUID.randomUUID();
    private final UUID generation = UUID.randomUUID();

    @Test
    void anonymousCannotReadRegistry() {
        http.get().uri(url()).exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(client);
    }

    @Test
    void memberCanReadCustomTypeMetadata() {
        authenticated();
        when(client.list(any(), any())).thenReturn(Mono.just(new ListProjectIssueTypesDto(List.of(type()))));
        http.get().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
                .expectStatus().isOk().expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.items[0].code").isEqualTo("REQUEST")
                .jsonPath("$.items[0].icon").isEqualTo("headset")
                .jsonPath("$.items[0].generation").isEqualTo(generation.toString())
                .jsonPath("$.items[0].pendingOperationId").doesNotExist();
    }

    @Test
    void createReturns201AndPatchUpdatesMetadata() {
        authenticated();
        when(client.create(any(), any(), any())).thenReturn(Mono.just(type()));
        http.post().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"code\":\"REQUEST\",\"name\":\"Заявка\",\"color\":\"teal\",\"icon\":\"headset\"}")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").exchange().expectStatus().isCreated();
        when(client.update(any(), any(), any(), any())).thenReturn(Mono.just(type()));
        http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"name\":\"Заявка\"}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo("REQUEST");
    }

    @Test
    void explicitNullOrFractionalSortOrderDoesNotReachBackend() {
        authenticated();
        for (String body : List.of("{\"name\":null}", "{\"sortOrder\":1.5}", "{\"sortOrder\":2147483648}",
                "{\"name\":\"Name\",\"code\":\"NEW_CODE\"}")) {
            http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .bodyValue(body).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(client);
    }

    @Test
    void typeWithoutIconIsServedWithExplicitNullIconAndItsShape() {
        authenticated();
        when(client.list(any(), any())).thenReturn(Mono.just(new ListProjectIssueTypesDto(List.of(new ProjectIssueTypeDto(
                "BUG", "Bug", ProjectIssueTypeColorDto.RED, ProjectIssueTypeShapeDto.CIRCLE, null, 20)))));
        http.get().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.items[0].shape").isEqualTo("circle")
                .jsonPath("$.items[0]").value(java.util.Map.class, item -> org.assertj.core.api.Assertions
                        .assertThat(item).containsEntry("icon", null));
    }

    @Test
    void createWithoutShapeOrIconReachesRegistryAndInvalidShapeDoesNot() {
        authenticated();
        when(client.create(any(), any(), any())).thenReturn(Mono.just(type()));
        for (String body : List.of("{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\"}",
                "{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"icon\":null}",
                "{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"shape\":\"diamond\",\"icon\":\"flag\"}")) {
            http.post().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").bodyValue(body)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json").exchange().expectStatus().isCreated();
        }
        verify(client, times(3)).create(any(), any(), any());
        clearInvocations(client);
        for (String body : List.of("{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"shape\":\"hexagon\"}",
                "{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"shape\":null}")) {
            http.post().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").bodyValue(body)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json").exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(client);
    }

    @Test
    void patchNullIconReachesRegistryAsClearWhileNullShapeIsRefused() {
        authenticated();
        when(client.update(any(), any(), any(), any())).thenReturn(Mono.just(type()));
        http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"icon\":null,\"shape\":\"circle\"}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isOk();
        var captor = org.mockito.ArgumentCaptor.forClass(PatchProjectIssueTypeRequestDto.class);
        verify(client).update(any(), any(), captor.capture(), any());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getIcon()).isEqualTo(nullable.NullableField.of(null));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getShape()).isEqualTo("circle");
        clearInvocations(client);
        http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"shape\":null}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(client);
    }

    @Test
    void emptyPatchIsStillRefusedByRegistry() {
        authenticated();
        when(client.update(any(), any(), any(), any())).thenReturn(Mono.error(Status.INVALID_ARGUMENT
                .withDescription("At least one field is required").asRuntimeException()));
        http.patch().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void memberCannotModifyType() {
        authenticated();
        when(client.delete(any(), any(), any(), any())).thenReturn(Mono.error(Status.PERMISSION_DENIED
                .withDescription("Project ADMIN role is required").asRuntimeException()));
        http.delete().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void orderEndpointPreservesOrderedListAndDuplicatesForRegistryValidation() {
        authenticated();
        when(client.reorder(any(), any(), any())).thenAnswer(inv -> {
            ReorderProjectIssueTypesRequestDto body = inv.getArgument(1);
            org.assertj.core.api.Assertions.assertThat(body.getCodes()).containsExactly("BUG", "TASK", "BUG");
            return Mono.error(Status.INVALID_ARGUMENT.withDescription("Duplicate code").asRuntimeException());
        });
        http.put().uri(url()+"/order").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("{\"codes\":[\"BUG\",\"TASK\",\"BUG\"]}")
                .exchange().expectStatus().isBadRequest();
        verify(client).reorder(any(), any(), any());
    }

    @Test
    void staleRegistryOrderPreserves409AndIssueTypesChangedReason() {
        authenticated();
        when(client.reorder(any(), any(), any())).thenReturn(Mono.error(Status.ABORTED
                .withDescription("Issue type registry changed").asRuntimeException(mapper.GrpcErrorDetails.encode(
                        java.util.Map.of("reason", "ISSUE_TYPES_CHANGED")))));
        http.put().uri(url()+"/order").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"codes\":[\"TASK\",\"BUG\"]}")
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("ABORTED")
                .jsonPath("$.details.reason").isEqualTo("ISSUE_TYPES_CHANGED");
    }

    @Test
    void onlyInUseTypeDeletionCarriesIssueCount() {
        authenticated();
        for (var details : List.of(java.util.Map.of("reason", "LAST_ISSUE_TYPE"),
                java.util.Map.of("reason", "ISSUE_TYPE_IN_USE", "issueCount", "7"))) {
            when(client.delete(any(), any(), any(), any())).thenReturn(Mono.error(Status.FAILED_PRECONDITION
                    .withDescription("Cannot delete issue type").asRuntimeException(mapper.GrpcErrorDetails.encode(details))));
            var body = http.delete().uri(url()+"/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.details.reason").isEqualTo(details.get("reason"));
            if (details.containsKey("issueCount")) body.jsonPath("$.details.issueCount").isEqualTo("7");
            else body.jsonPath("$.details.issueCount").doesNotExist();
        }
    }

    @Test
    void deleteWithoutBodyAndWithEmptyBodyKeepLegacyCommandAndReturn204() {
        authenticated();
        when(client.delete(any(), any(), any(), any())).thenAnswer(inv -> {
            DeleteProjectIssueTypeRequestDto command = inv.getArgument(2);
            org.assertj.core.api.Assertions.assertThat(command.getMoveIssuesTo()).isNull();
            org.assertj.core.api.Assertions.assertThat(command.getExpectedGeneration()).isNull();
            org.assertj.core.api.Assertions.assertThat(command.getStatusMappings()).isNullOrEmpty();
            return Mono.empty();
        });
        http.delete().uri(url() + "/REQUEST").header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isNoContent();
        http.method(org.springframework.http.HttpMethod.DELETE).uri(url() + "/REQUEST")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isNoContent();
        verify(client, times(2)).delete(any(), any(), any(), any());
    }

    @Test
    void deleteMoveForwardsMappingAndGenerationWithAuthenticatedContext() {
        authenticated();
        when(client.delete(any(), any(), any(), any())).thenAnswer(inv -> {
            org.assertj.core.api.Assertions.assertThat(inv.<UUID>getArgument(0)).isEqualTo(project);
            org.assertj.core.api.Assertions.assertThat(inv.<String>getArgument(1)).isEqualTo("REQUEST");
            DeleteProjectIssueTypeRequestDto command = inv.getArgument(2);
            org.assertj.core.api.Assertions.assertThat(command.getMoveIssuesTo()).isEqualTo("TASK");
            org.assertj.core.api.Assertions.assertThat(command.getStatusMappings()).containsExactlyEntriesOf(
                    java.util.Map.of("TRIAGE", "TODO"));
            org.assertj.core.api.Assertions.assertThat(command.getExpectedGeneration()).isEqualTo(generation);
            GatewayContext context = inv.getArgument(3);
            org.assertj.core.api.Assertions.assertThat(context.userContext().userId()).isEqualTo(user.toString());
            return Mono.empty();
        });
        moveDelete("{\"moveIssuesTo\":\"TASK\",\"statusMappings\":{\"TRIAGE\":\"TODO\"},\"expectedGeneration\":\"%s\"}".formatted(generation))
                .expectStatus().isNoContent();
        verify(client).delete(any(), any(), any(), any());
    }

    @Test
    void moveErrorsPreserveUnmappedStatusesGenerationAndPendingOperationDetails() {
        authenticated();
        var errors = java.util.List.of(
                new Object[]{Status.FAILED_PRECONDITION, 400, java.util.Map.of("reason", "ISSUE_STATUSES_UNMAPPED", "statusKeys", "TRIAGE,REVIEW", "targetStatusKeys", "TODO,DONE")},
                new Object[]{Status.FAILED_PRECONDITION, 400, java.util.Map.of("reason", "WORKFLOW_MIGRATION_LIMIT", "limit", "5000")},
                new Object[]{Status.ABORTED, 409, java.util.Map.of("reason", "ISSUE_TYPE_GENERATION_REQUIRED")},
                new Object[]{Status.ABORTED, 409, java.util.Map.of("reason", "ISSUE_TYPE_CHANGED")},
                new Object[]{Status.UNAVAILABLE, 503, java.util.Map.of("reason", "ISSUE_TYPE_DELETION_PENDING", "operationId", generation.toString())});
        for (var error : errors) {
            @SuppressWarnings("unchecked") var details = (java.util.Map<String, String>) error[2];
            when(client.delete(any(), any(), any(), any())).thenReturn(Mono.error(((Status) error[0])
                    .withDescription("Issue type deletion could not complete")
                    .asRuntimeException(mapper.GrpcErrorDetails.encode(details))));
            var result = moveDelete("{\"moveIssuesTo\":\"TASK\"}").expectStatus().isEqualTo((int) error[1]).expectBody();
            for (var entry : details.entrySet())
                result.jsonPath("$.details." + entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void malformedMoveBodyIsRejectedBeforeCallingProjectService() {
        authenticated();
        for (String body : java.util.List.of("{\"moveIssuesTo\":\"\"}", "{\"moveIssuesTo\":\"bad-key\"}",
                "{\"moveIssuesTo\":\"TASK\",\"expectedGeneration\":\"invalid\"}"))
            moveDelete(body).expectStatus().isBadRequest();
        verifyNoInteractions(client);
    }

    @Test
    void readPatCannotStartADeletionEvenWithMoveBody() {
        authenticated();
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder()
                .userId(user.toString()).email("user@example.com").status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER).tokenKind(GatewayTokenKind.PAT).tokenId(UUID.randomUUID().toString())
                .scopes(java.util.List.of("read")).build());
        moveDelete("{\"moveIssuesTo\":\"TASK\"}").expectStatus().isForbidden();
        verifyNoInteractions(client);
    }

    private WebTestClient.ResponseSpec moveDelete(String body) {
        return http.method(org.springframework.http.HttpMethod.DELETE).uri(url() + "/REQUEST")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private String url() { return "/api/v1/projects/" + project + "/issue-types"; }

    private ProjectIssueTypeDto type() {
        return new ProjectIssueTypeDto("REQUEST", "Заявка", ProjectIssueTypeColorDto.TEAL,
                ProjectIssueTypeShapeDto.SQUARE, ProjectIssueTypeIconDto.HEADSET, 40).generation(generation);
    }

    private void authenticated() {
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(Mono.just(ValidateAccessTokenResponse
                .newBuilder().setUserContext(UserContext.newBuilder().setUserId(user.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder()
                .userId(user.toString()).email("user@example.com").status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER).build());
    }
}
