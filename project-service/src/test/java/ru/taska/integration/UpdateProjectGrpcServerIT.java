package ru.taska.integration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.UpdateProjectRequest;
import ru.taska.api.project.v1.UpdateProjectRequestBody;
import ru.taska.domain.Project;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.repository.ProjectRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Проверяет, что rpc UpdateProject реально ЗАБИНЖЕН в gRPC-сервер project-service
 * ({@code GrpcProjectServiceAdapter}), а не только реализован во внутреннем сервисном слое.
 *
 * <p>В отличие от {@link UpdateProjectIT} (вызывает {@code ProjectService} напрямую как Spring-бин,
 * минуя транспорт), этот тест поднимает настоящий {@link ManagedChannel} и стучится в него через
 * реальный клиентский стаб {@link ReactorProjectServiceGrpc.ReactorProjectServiceStub} — ровно так,
 * как это делает api-gateway в проде. Именно такой тест поймал бы забытое переопределение метода
 * в {@code GrpcProjectServiceAdapter} (см. review.md, CRITICAL Finding #0).</p>
 */
class UpdateProjectGrpcServerIT extends AbstractIT {

    private static ManagedChannel channel;
    private static ReactorProjectServiceGrpc.ReactorProjectServiceStub stub;

    private static final String REQUEST_ID = "req-grpc-001";
    private static final String NODE_ID = "project-service";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    private Project project;

    @BeforeAll
    static void setUpChannel() {
        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_SERVER_PORT)
                .usePlaintext()
                .build();
        stub = ReactorProjectServiceGrpc.newReactorStub(channel);
    }

    @AfterAll
    static void tearDownChannel() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }

    @BeforeEach
    void refillDb() {
        projectRepository.deleteAll().block();

        project = projectRepository.save(Project.builder()
                .projectKey("GRPCIT")
                .name("Original name")
                .createdBy(ADMIN_ID)
                .build()).block();

        projectMemberRepository.save(ProjectMember.builder()
                .projectId(project.getId())
                .userId(ADMIN_ID)
                .role(ProjectRole.ADMIN)
                .addedBy(ADMIN_ID)
                .addedAt(Instant.now())
                .build()).block();
    }

    @Test
    void updateProject_ThroughRealGrpcServer_Success() {
        UpdateProjectRequest request = UpdateProjectRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(NODE_ID).build())
                .setBody(UpdateProjectRequestBody.newBuilder()
                        .setProjectId(project.getId().toString())
                        .setActorUserId(ADMIN_ID.toString())
                        .setName("Updated via real gRPC")
                        .build())
                .build();

        StepVerifier.create(stub.updateProject(Mono.just(request)))
                .assertNext(response -> Assertions.assertThat(response.getName()).isEqualTo("Updated via real gRPC"))
                .verifyComplete();
    }

    @Test
    void updateProject_NonExistentProject_ThroughRealGrpcServer_ReturnsNotFound() {
        UpdateProjectRequest request = UpdateProjectRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(NODE_ID).build())
                .setBody(UpdateProjectRequestBody.newBuilder()
                        .setProjectId(UUID.randomUUID().toString())
                        .setActorUserId(ADMIN_ID.toString())
                        .setName("Whatever")
                        .build())
                .build();

        StepVerifier.create(stub.updateProject(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(StatusRuntimeException.class);
                    StatusRuntimeException ex = (StatusRuntimeException) error;
                    Assertions.assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                })
                .verify();
    }
}
