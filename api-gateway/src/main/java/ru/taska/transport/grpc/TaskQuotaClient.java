package ru.taska.transport.grpc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.GetProjectTaskQuotasRequest;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.TaskQuotaDto;
import ru.taska.security.tenant.RpcContextBridge;

/** One scoped RPC per page. Unavailable usage fails rather than inventing a zero. */
@Component
public final class TaskQuotaClient {
  private final ReactorIssueServiceGrpc.ReactorIssueServiceStub stub;
  private final boolean nativeContext;

  public TaskQuotaClient(
      ReactorIssueServiceGrpc.ReactorIssueServiceStub stub,
      @Value("${taska.rpc.context.enabled:false}") boolean nativeContext) {
    this.stub = stub;
    this.nativeContext = nativeContext;
  }

  public Mono<Map<String, TaskQuotaDto>> read(List<String> ids, GatewayContext context) {
    if (!nativeContext || ids.isEmpty()) return Mono.just(Map.of());
    if (ids.size() > 100)
      return Mono.error(new IllegalArgumentException("At most 100 project IDs"));
    return RpcContextBridge.outbound(
            () ->
                stub.withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                    .getProjectTaskQuotas(
                        GetProjectTaskQuotasRequest.newBuilder()
                            .setHeader(ru.taska.mapper.MappingUtils.buildGrpcHeader(context))
                            .addAllProjectIds(ids)
                            .build()))
        .map(
            response -> {
              var byId =
                  response.getItemsList().stream()
                      .collect(Collectors.toMap(x -> x.getProjectId(), Function.identity()));
              if (!byId.keySet().equals(new java.util.HashSet<>(ids)))
                throw new IllegalStateException("Incomplete task quota response");
              return byId.entrySet().stream()
                  .collect(
                      Collectors.toMap(
                          Map.Entry::getKey,
                          entry -> {
                            var q = entry.getValue();
                            if (q.getUsed() < 0
                                || (!q.getUnlimited() && (!q.hasLimit() || q.getLimit() < 0))
                                || (q.getUnlimited() && q.hasLimit()))
                              throw new IllegalStateException("Invalid task quota response");
                            var result = new TaskQuotaDto();
                            result.setUsed(q.getUsed());
                            result.setUnlimited(q.getUnlimited());
                            if (q.hasLimit()) result.setLimit(q.getLimit());
                            return result;
                          }));
            });
  }
}
