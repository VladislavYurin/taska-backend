package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.grpc.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.workflow.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.*;
import ru.taska.domain.dto.*;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.transport.grpc.GrpcWorkflowMutationClient;

class WorkflowMutationContextTest {
  @Test
  void everyMutationCapturesItsSubscriberAuthorityAcrossSchedulerSwitchAndResubscription() {
    var seen = Collections.synchronizedList(new ArrayList<VerifiedTenantContext>());
    Channel channel =
        new Channel() {
          public String authority() {
            return "owned-test";
          }

          public <Q, R> ClientCall<Q, R> newCall(
              MethodDescriptor<Q, R> method, CallOptions options) {
            return new ClientCall<>() {
              Listener<R> listener;

              public void start(Listener<R> listener, Metadata metadata) {
                this.listener = listener;
                seen.add(RpcContextBridge.OUTBOUND_INHERITED.get());
              }

              public void request(int n) {}

              public void cancel(String message, Throwable cause) {}

              public void sendMessage(Q q) {}

              @SuppressWarnings("unchecked")
              public void halfClose() {
                listener.onMessage((R) ProjectWorkflow.getDefaultInstance());
                listener.onClose(Status.OK, new Metadata());
              }
            };
          }
        };
    var service = new GrpcClientProperties.Service("owned-test", 1, Duration.ofSeconds(3));
    var properties = new GrpcClientProperties(service, service, service, service, service, service);
    var mapper = mock(WorkflowMapper.class);
    when(mapper.toProjectWorkflowDto(any())).thenReturn(new ProjectWorkflowDto());
    var client =
        new GrpcWorkflowMutationClient(
            ReactorWorkflowServiceGrpc.newReactorStub(channel), properties, mapper);
    UUID actor = UUID.randomUUID(), tenant = UUID.randomUUID(), project = UUID.randomUUID();
    var gateway =
        new GatewayContext(
            "workflow-context-test",
            "api-gateway",
            GatewayUserContext.builder().userId(actor.toString()).build());
    for (var operation : GrpcWorkflowMutationClient.Operation.values()) {
      var work =
          client.mutate(
              operation,
              project,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              new WorkflowPatchBody() {},
              null,
              null,
              gateway);
      for (int i = 0; i < 2; i++) {
        var wire =
            RequestContext.newBuilder()
                .setContext(
                    TrustedTenantContext.newBuilder()
                        .setActorUserId(actor.toString())
                        .setTenantId(tenant.toString()))
                .build();
        var context =
            new VerifiedTenantContext(
                actor.toString(),
                tenant.toString(),
                UUID.randomUUID().toString(),
                "gatewayRequest",
                Set.of("read", "write"),
                wire.toByteArray());
        work.subscribeOn(Schedulers.parallel())
            .contextWrite(context::install)
            .block(Duration.ofSeconds(3));
        assertSame(context, seen.get(seen.size() - 1), operation.toString());
      }
    }
    assertEquals(12, seen.size());
  }
}
