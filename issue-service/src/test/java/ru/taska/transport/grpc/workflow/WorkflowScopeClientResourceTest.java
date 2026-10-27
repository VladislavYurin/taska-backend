package ru.taska.transport.grpc.workflow;

import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.CheckProjectWorkflowScopeRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.domain.IssueType;
import ru.taska.mapper.IssueMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real default-sized TCP transport rather than increasing the aggregate response limit. */
class WorkflowScopeClientResourceTest {
    @Test
    void initialAndVersionChecksStayBoundedWhenThreeIndividuallyBoundedGraphsOverflowTheAggregate() throws Exception {
        UUID project=UUID.randomUUID(),actor=UUID.randomUUID(),workflow=UUID.randomUUID();
        var large=largeWorkflow(workflow);
        assertThat(large.getSerializedSize()).isLessThan(3*1024*1024);
        var aggregate=ListProjectWorkflowsResponse.newBuilder();
        for(int i=0;i<3;i++) aggregate.addWorkflows(ProjectWorkflow.newBuilder().setWorkflow(large).addIssueTypeKeys("TYPE_"+i));
        var response=aggregate.build();
        assertThat(response.getSerializedSize()).isGreaterThan(4*1024*1024);
        var checks=new ArrayList<CheckProjectWorkflowScopeRequest>();
        var listCalls=new AtomicInteger();
        Server server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0))
                .addService(new ReactorWorkflowServiceGrpc.WorkflowServiceImplBase() {
                    @Override public Mono<ListProjectWorkflowsResponse> listProjectWorkflows(Mono<ListProjectWorkflowsRequest> request) {
                        return request.map(ignored->{listCalls.incrementAndGet();return response;});
                    }
                    @Override public Mono<Empty> checkProjectWorkflowScope(Mono<CheckProjectWorkflowScopeRequest> request) {
                        return request.map(command->{checks.add(command);return Empty.getDefaultInstance();});
                    }
                }).build().start();
        ManagedChannel channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        try {
            var stub=ReactorWorkflowServiceGrpc.newReactorStub(channel);
            assertThatThrownBy(()->stub.listProjectWorkflows(ListProjectWorkflowsRequest.getDefaultInstance()).block(Duration.ofSeconds(10)))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,error->assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
            var client=new GrpcWorkflowServiceClient(stub,new IssueMapper(new tools.jackson.databind.ObjectMapper()));
            client.requireInitialStatus("initial","issue",project,IssueType.TASK,actor,"START").block(Duration.ofSeconds(10));
            client.requireWorkflowVersion("legacy","issue",project,actor,List.of(IssueType.TASK,IssueType.BUG),workflow,7).block(Duration.ofSeconds(10));
            assertThat(listCalls.get()).isEqualTo(1);
            assertThat(checks).hasSize(2);
            var initial=checks.get(0);
            assertThat(initial.getProjectId()).isEqualTo(project.toString());assertThat(initial.getActorUserId()).isEqualTo(actor.toString());
            assertThat(initial.getHeader().getRequestId()).isEqualTo("initial");assertThat(initial.getHeader().getNodeId()).isEqualTo("issue");
            assertThat(initial.getIssueTypeKeysList()).containsExactly("TASK");assertThat(initial.getStatusKey()).isEqualTo("START");
            assertThat(initial.hasExpectedWorkflowId()).isFalse();assertThat(initial.hasExpectedWorkflowVersion()).isFalse();
            var legacy=checks.get(1);
            assertThat(legacy.getIssueTypeKeysList()).containsExactly("TASK","BUG");assertThat(legacy.hasStatusKey()).isFalse();
            assertThat(legacy.getExpectedWorkflowId()).isEqualTo(workflow.toString());assertThat(legacy.getExpectedWorkflowVersion()).isEqualTo(7);
            assertThat(legacy.getProjectId()).isEqualTo(project.toString());assertThat(legacy.getActorUserId()).isEqualTo(actor.toString());
        } finally {
            channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        }
    }

    private WorkflowResponse largeWorkflow(UUID id) {
        var result=WorkflowResponse.newBuilder().setId(id.toString()).setName("Large graph").setVersion(7);
        var statuses=new ArrayList<String>();
        for(int i=0;i<100;i++) {
            String status=UUID.randomUUID().toString();statuses.add(status);
            result.addStatuses(WorkflowStatus.newBuilder().setId(status).setStatusKey("STATUS_"+i).setName("S".repeat(128)).setSortOrder(i));
        }
        for(int i=0;i<6000;i++) {
            int from=i/99,to=i%99;
            if(to>=from)to++;
            result.addTransitions(WorkflowTransition.newBuilder().setId(UUID.randomUUID().toString()).setFromStatusId(statuses.get(from))
                    .setToStatusId(statuses.get(to)).setName("T".repeat(128)).setSortOrder(i));
        }
        return result.build();
    }
}
