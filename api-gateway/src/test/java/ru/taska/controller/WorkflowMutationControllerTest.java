package ru.taska.controller;

import static org.mockito.ArgumentMatchers.*;
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
import ru.taska.transport.grpc.GrpcWorkflowMutationClient;

@WebFluxTest(controllers = WorkflowMutationController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class})
class WorkflowMutationControllerTest {
    @Autowired WebTestClient http;
    @MockitoBean GrpcWorkflowMutationClient client;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;
    private final UUID project = UUID.randomUUID(), workflow = UUID.randomUUID(), user = UUID.randomUUID(), status = UUID.randomUUID();

    @Test
    void anonymousCannotMutateWorkflow() {
        http.patch().uri(url()).bodyValue("{\"name\":\"Workflow\"}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(client);
    }

    @Test
    void patchReturnsFullWorkflowEnvelope() {
        authenticated();
        reply(Mono.just(response()));
        http.patch().uri(url() + "/statuses/" + status).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"name\":\"Проверка\",\"version\":4}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.workflow.id").isEqualTo(workflow.toString())
                .jsonPath("$.issueTypes[0]").isEqualTo("TASK").jsonPath("$.shared").isEqualTo(false);
    }

    @Test
    void invalidMergePatchValuesNeverReachBackend() {
        authenticated();
        for (String body : List.of("{\"name\":null}", "{\"sortOrder\":1.5}", "{\"statusKey\":\"CHANGED\"}", "{\"version\":2147483648}")) {
            http.patch().uri(url() + "/statuses/" + status).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .bodyValue(body).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(client);
    }

    @Test
    void deletingWorkflowAcceptsEmptyBodyAndRunsOnlyOnce() {
        authenticated();
        reply(Mono.just(response()));
        http.delete().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange().expectStatus().isOk();
        verify(client).mutate(any(), any(), any(), nullable(UUID.class), nullable(UUID.class), any(),
                nullable(Integer.class), nullable(String.class), any());
    }

    @Test
    void deletingWorkflowAcceptsExplicitJsonMapping() {
        authenticated();
        reply(Mono.just(response()));
        http.method(org.springframework.http.HttpMethod.DELETE).uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"version\":4,\"statusMappings\":{\"REVIEW\":\"DONE\"}}")
                .exchange().expectStatus().isOk();
        verify(client).mutate(any(), any(), any(), nullable(UUID.class), nullable(UUID.class),
                argThat(body -> body.getStatusMappings().equals(java.util.Map.of("REVIEW", "DONE")) && body.getVersion() == 4),
                nullable(Integer.class), nullable(String.class), any());
    }

    @Test
    void untypedDeleteBodyCannotBeSilentlyIgnored() {
        authenticated();
        var data = new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(
                "{\"statusMappings\":{\"REVIEW\":\"DONE\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        http.method(org.springframework.http.HttpMethod.DELETE).uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .body(org.springframework.web.reactive.function.BodyInserters.fromDataBuffers(reactor.core.publisher.Flux.just(data)))
                .exchange().expectStatus().isEqualTo(415);
        verifyNoInteractions(client);
    }

    @Test
    void staleVersionIs409() {
        authenticated();
        reply(Mono.error(Status.ABORTED.withDescription("Workflow version changed").asRuntimeException()));
        http.patch().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"name\":\"New\",\"version\":3}").header(HttpHeaders.CONTENT_TYPE, "application/json")
                .exchange().expectStatus().isEqualTo(409);
    }


    @Test
    void dtoBoundariesAreRejectedAtGatewayWithInvalidArgument() {
        authenticated();
        record Invalid(String suffix, String body) {}
        for (Invalid invalid : List.of(
                new Invalid("", "{}"), new Invalid("", "{\"name\":\"\"}"),
                new Invalid("", "{\"name\":\"   \"}"),
                new Invalid("", "{\"name\":\"" + "x".repeat(101) + "\"}"),
                new Invalid("", "{\"name\":\"Name\",\"version\":0}"),
                new Invalid("/statuses/" + status, "{\"name\":\"\"}"),
                new Invalid("/statuses/" + status, "{\"name\":\"" + "x".repeat(101) + "\"}"),
                new Invalid("/statuses/" + status, "{\"sortOrder\":-1}"),
                new Invalid("/statuses/" + status, "{\"version\":0,\"name\":\"Name\"}"),
                new Invalid("/transitions/" + status, "{\"name\":\"\"}"),
                new Invalid("/transitions/" + status, "{\"name\":\"" + "x".repeat(101) + "\"}"),
                new Invalid("/transitions/" + status, "{\"sortOrder\":-1}"),
                new Invalid("/transitions/" + status, "{\"version\":0,\"name\":\"Name\"}"))) {
            http.patch().uri(url() + invalid.suffix()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .bodyValue(invalid.body()).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_ARGUMENT");
        }
        http.method(org.springframework.http.HttpMethod.DELETE).uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("{\"version\":0}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(client);
    }

    @Test
    void dtoBoundaryValuesAreAccepted() {
        authenticated();
        reply(Mono.just(response()));
        http.patch().uri(url() + "/statuses/" + status).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .bodyValue("{\"name\":\"" + "x".repeat(100) + "\",\"sortOrder\":0,\"version\":1}")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").exchange().expectStatus().isOk();
    }

    @Test
    void bindingPatchNeedsNoNameAndPreservesItsCompleteSetAndMappings() {
        authenticated(); reply(Mono.just(response()));
        http.patch().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"version\":4,\"issueTypes\":[\"TASK\",\"EPIC\"],\"statusMappings\":{\"BACKLOG\":\"TODO\"}}")
                .exchange().expectStatus().isOk();
        verify(client).mutate(any(), any(), any(), nullable(UUID.class), nullable(UUID.class),
                argThat(command -> command.getName() == null && command.getVersion() == 4
                        && command.getIssueTypes().equals(List.of("TASK", "EPIC"))
                        && command.getStatusMappings().equals(java.util.Map.of("BACKLOG", "TODO"))),
                nullable(Integer.class), nullable(String.class), any());
    }

    @Test
    void emptyBindingSetReachesBackendAndRetainsStableReason() {
        authenticated(); reply(Mono.error(new ru.taska.exception.DomainException(ru.taska.exception.DomainStatus.INVALID_ARGUMENT,
                "Workflow must have issue types", java.util.Map.of("reason", "WORKFLOW_ISSUE_TYPES_EMPTY"))));
        http.patch().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"version\":4,\"issueTypes\":[]}")
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.details.reason").isEqualTo("WORKFLOW_ISSUE_TYPES_EMPTY");
        verify(client).mutate(any(), any(), any(), nullable(UUID.class), nullable(UUID.class),
                argThat(command -> command.getIssueTypes() != null && command.getIssueTypes().isEmpty()),
                nullable(Integer.class), nullable(String.class), any());
    }

    @Test
    void bindingPatchRejectsNullDuplicateMalformedAndMissingVersionBeforeRpc() {
        authenticated();
        for (String body : List.of("{\"issueTypes\":[\"TASK\"]}", "{\"version\":1}",
                "{\"version\":1,\"issueTypes\":null}", "{\"version\":1,\"issueTypes\":[\"TASK\",\"TASK\"]}",
                "{\"version\":1,\"issueTypes\":[\"bad-key\"]}", "{\"version\":1,\"issueTypes\":[null]}",
                "{\"version\":1,\"issueTypes\":[\"TASK\"],\"statusMappings\":null}",
                "{\"version\":1,\"issueTypes\":[\"TASK\"],\"statusMappings\":{\"BACKLOG\":null}}",
                "{\"version\":1,\"issueTypes\":[\"TASK\"],\"statusMappings\":{\"bad-key\":\"TODO\"}}",
                "{\"name\":\"Rename\",\"statusMappings\":{\"BACKLOG\":\"TODO\"}}")) {
            http.patch().uri(url()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchange().expectStatus().isBadRequest();
        }
        verifyNoInteractions(client);
    }

    private void reply(Mono<ProjectWorkflowDto> response) {
        when(client.mutate(any(), any(), any(), nullable(UUID.class), nullable(UUID.class), any(),
                nullable(Integer.class), nullable(String.class), any())).thenReturn(response);
    }

    private ProjectWorkflowDto response() {
        var value = new WorkflowResponseDto(); value.setId(workflow); value.setName("Workflow"); value.setVersion(5);
        return new ProjectWorkflowDto(value, List.of("TASK"), false);
    }

    private String url() { return "/api/v1/projects/" + project + "/workflows/" + workflow; }

    private void authenticated() {
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(user.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder().userId(user.toString())
                .displayName("User").email("user@example.com").status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
    }
}
