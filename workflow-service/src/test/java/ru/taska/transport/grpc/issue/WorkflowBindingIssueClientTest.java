package ru.taska.transport.grpc.issue;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.registry.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** The actual TCP codec must preserve unknown enum values and typed rejection acknowledgements. */
class WorkflowBindingIssueClientTest {
    private Server server;
    private ManagedChannel channel;
    private WorkflowBindingIssueClient client;
    private final AtomicReference<TypeOperationOutcome> response=new AtomicReference<>();
    private final AtomicReference<TypeOperationKey> received=new AtomicReference<>();
    private final AtomicReference<Throwable> transportError=new AtomicReference<>();
    private final UUID project=UUID.randomUUID(),operation=UUID.randomUUID();

    @BeforeEach
    void start() throws Exception {
        server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0))
                .addService(new ReactorWorkflowBindingIssueMigrationGrpc.WorkflowBindingIssueMigrationImplBase() {
                    @Override public Mono<TypeOperationOutcome> release(Mono<TypeOperationKey> request) {
                        return request.flatMap(key -> {
                            received.set(key);
                            return transportError.get()==null?Mono.just(response.get()):Mono.error(transportError.get());
                        });
                    }
                }).build().start();
        channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        client=new WorkflowBindingIssueClient(ReactorIssueServiceGrpc.newReactorStub(channel));
    }
    @AfterEach
    void close() throws Exception {
        if (channel!=null) channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if (server!=null) server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
    }
    @Test
    void appliedAndCancelledAcknowledgeReleaseAndForwardOnlyTheOperationIdentity() {
        for (var state:List.of(TypeOperationState.TYPE_OPERATION_STATE_APPLIED,TypeOperationState.TYPE_OPERATION_STATE_CANCELLED)) {
            response.set(TypeOperationOutcome.newBuilder().setState(state).build());
            StepVerifier.create(client.release(project,operation)).verifyComplete();
            assertThat(received.get().getProjectId()).isEqualTo(project.toString());
            assertThat(received.get().getOperationId()).isEqualTo(operation.toString());
        }
    }
    @Test
    void rejectedEmptyAndFutureStateDoNotAcknowledgeRelease() {
        for (var outcome:List.of(TypeOperationOutcome.newBuilder().setState(TypeOperationState.TYPE_OPERATION_STATE_REJECTED)
                        .setRejectionStatus("UNAVAILABLE").setRejectionMessage("Receipt unavailable").build(),
                TypeOperationOutcome.getDefaultInstance(),TypeOperationOutcome.newBuilder().setStateValue(71).build())) {
            response.set(outcome);
            StepVerifier.create(client.release(project,operation)).expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOfSatisfying(DomainException.class,domain -> {
                    assertThat(domain.getStatus()).isEqualTo(DomainStatus.UNAVAILABLE);
                    assertThat(domain.getDetails()).containsEntry("reason","WORKFLOW_BINDING_RELEASE_PENDING");
                });
            }).verify(Duration.ofSeconds(5));
        }
    }
    @Test
    void transportFailureAlsoLeavesReleaseUnacknowledged() {
        transportError.set(Status.UNAVAILABLE.withDescription("Peer unavailable").asRuntimeException());
        StepVerifier.create(client.release(project,operation))
                .expectErrorSatisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAVAILABLE))
                .verify(Duration.ofSeconds(5));
    }
}
