package ru.taska.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.GetProjectTaskQuotasRequest;
import ru.taska.api.issue.v1.GetProjectTaskQuotasResponse;
import ru.taska.api.issue.v1.ProjectTaskQuota;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;

class TaskQuotaClientTest {
  final ReactorIssueServiceGrpc.ReactorIssueServiceStub stub =
      mock(ReactorIssueServiceGrpc.ReactorIssueServiceStub.class);
  final GatewayContext context =
      new GatewayContext(
          "request", "gateway", GatewayUserContext.builder().userId("actor").build());

  TaskQuotaClient client() {
    when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
    return new TaskQuotaClient(stub, true);
  }

  @Test
  void pageUsesOneRpcAndLifetimeUsageIncludingDeletedIssues() {
    var client = client();
    when(stub.getProjectTaskQuotas(any(GetProjectTaskQuotasRequest.class)))
        .thenReturn(
            Mono.just(
                GetProjectTaskQuotasResponse.newBuilder()
                    .addItems(
                        ProjectTaskQuota.newBuilder().setProjectId("a").setUsed(50).setLimit(50))
                    .addItems(
                        ProjectTaskQuota.newBuilder()
                            .setProjectId("b")
                            .setUsed(72)
                            .setUnlimited(true))
                    .build()));
    var result = client.read(List.of("a", "b"), context).block();
    assertEquals(50L, result.get("a").getUsed());
    assertEquals(50L, result.get("a").getLimit());
    assertTrue(result.get("b").getUnlimited());
    assertNull(result.get("b").getLimit());
    verify(stub, times(1))
        .getProjectTaskQuotas(
            argThat(
                (GetProjectTaskQuotasRequest q) ->
                    q.getProjectIdsList().equals(List.of("a", "b"))));
  }

  @Test
  void unavailableOrIncompleteReadNeverInventsZeroUsage() {
    var client = client();
    when(stub.getProjectTaskQuotas(any(GetProjectTaskQuotasRequest.class)))
        .thenReturn(Mono.error(io.grpc.Status.UNAVAILABLE.asRuntimeException()));
    assertThrows(
        io.grpc.StatusRuntimeException.class, () -> client.read(List.of("a"), context).block());
    when(stub.getProjectTaskQuotas(any(GetProjectTaskQuotasRequest.class)))
        .thenReturn(Mono.just(GetProjectTaskQuotasResponse.getDefaultInstance()));
    assertThrows(IllegalStateException.class, () -> client.read(List.of("a"), context).block());
  }

  @Test
  void legacyProfileDoesNotCallNativeQuotaRpc() {
    assertTrue(new TaskQuotaClient(stub, false).read(List.of("a"), context).block().isEmpty());
    verifyNoInteractions(stub);
  }
}
