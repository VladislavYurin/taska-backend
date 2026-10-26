package ru.taska.controller;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import mapper.GrpcExceptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowMutationRequest;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.*;
import ru.taska.error.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.filter.*;
import ru.taska.mapper.ContextMapper;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowMutationClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** HTTP -> real gateway client -> gRPC wire -> workflow error envelope -> HTTP details. */
@WebFluxTest(controllers = WorkflowMutationController.class)
@Import({GatewayRequestExecutor.class, GatewayContextFactory.class, RequestIdProvider.class,
        BearerTokenExtractor.class, GatewayErrorHandler.class, RestErrorMapper.class,
        GrpcWorkflowMutationClient.class, WorkflowMapper.class, WorkflowMigrationErrorIntegrationTest.Wire.class})
class WorkflowMigrationErrorIntegrationTest {
    @Autowired WebTestClient http;
    @Autowired AtomicReference<DomainException> failure;
    @MockitoBean GrpcAuthServiceClient auth;
    @MockitoBean ContextMapper contexts;
    private final UUID project = UUID.randomUUID(), workflow = UUID.randomUUID(), actor = UUID.randomUUID();

    @BeforeEach
    void authenticated() {
        when(auth.validateAccessToken(any(), any(), any())).thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(actor.toString())).build()));
        when(contexts.mapToGatewayUserContext(any())).thenReturn(GatewayUserContext.builder().userId(actor.toString())
                .status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
    }

    @Test
    void bothDeletionRoutesExposeMappingReasonAndAllowedTargetsOverHttp() {
        for (String suffix : new String[]{"/statuses/" + UUID.randomUUID(), ""}) {
            String sourceKeys = suffix.isEmpty() ? "REVIEW,LEGACY" : "REVIEW";
            failure.set(new DomainException(DomainStatus.FAILED_PRECONDITION, "Workflow statuses have 3 issues",
                    Map.of("reason", "WORKFLOW_ISSUES_UNMAPPED", "issueCount", "3",
                            "statusKeys", sourceKeys, "targetStatusKeys", "TODO,DONE")));
            http.delete().uri(url() + suffix).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .header("X-Request-Id", "workflow-errors-427").exchange()
                    .expectStatus().isBadRequest().expectHeader().valueEquals("X-Request-Id", "workflow-errors-427")
                    .expectBody().jsonPath("$.code").isEqualTo("FAILED_PRECONDITION")
                    .jsonPath("$.details.reason").isEqualTo("WORKFLOW_ISSUES_UNMAPPED")
                    .jsonPath("$.details.issueCount").isEqualTo("3")
                    .jsonPath("$.details.statusKeys").isEqualTo(sourceKeys)
                    .jsonPath("$.details.targetStatusKeys").isEqualTo("TODO,DONE");
        }
    }

    @Test
    void limitIs400AndHasNoRateLimitRetryHeader() {
        failure.set(new DomainException(DomainStatus.FAILED_PRECONDITION, "Migration exceeds 5000 issues",
                Map.of("reason", "WORKFLOW_MIGRATION_LIMIT", "limit", "5000")));
        http.delete().uri(url() + "/statuses/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isBadRequest().expectHeader().doesNotExist("Retry-After")
                .expectBody().jsonPath("$.code").isEqualTo("FAILED_PRECONDITION")
                .jsonPath("$.details.reason").isEqualTo("WORKFLOW_MIGRATION_LIMIT")
                .jsonPath("$.details.limit").isEqualTo("5000");
    }

    private String url() { return "/api/v1/projects/" + project + "/workflows/" + workflow; }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wire {
        @Bean AtomicReference<DomainException> failure() { return new AtomicReference<>(); }

        @Bean(destroyMethod = "shutdownNow")
        Server workflowServer(AtomicReference<DomainException> failure) throws java.io.IOException {
            var endpoint = new ReactorWorkflowServiceGrpc.WorkflowServiceImplBase() {
                @Override public Mono<ProjectWorkflow> deleteWorkflowStatus(Mono<WorkflowMutationRequest> request) {
                    return request.flatMap(ignored -> Mono.error(GrpcExceptionMapper.toStatusRuntimeException(failure.get())));
                }
                @Override public Mono<ProjectWorkflow> deleteProjectWorkflow(Mono<WorkflowMutationRequest> request) {
                    return request.flatMap(ignored -> Mono.error(GrpcExceptionMapper.toStatusRuntimeException(failure.get())));
                }
            };
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(endpoint).build().start();
        }

        @Bean(destroyMethod = "shutdownNow") ManagedChannel channel(Server workflowServer) {
            return NettyChannelBuilder.forAddress("127.0.0.1", workflowServer.getPort()).usePlaintext().build();
        }

        @Bean ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowStub(ManagedChannel channel) {
            return ReactorWorkflowServiceGrpc.newReactorStub(channel);
        }

        @Bean GrpcClientProperties grpcClientProperties() {
            var value = mock(GrpcClientProperties.class, RETURNS_DEEP_STUBS);
            when(value.workflowService().deadlineDuration()).thenReturn(Duration.ofSeconds(5));
            return value;
        }
    }
}
