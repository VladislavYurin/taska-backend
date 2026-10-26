package ru.taska.transport.grpc.issue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.dto.WorkflowMutation;

@Component
@RequiredArgsConstructor
public class WorkflowIssueMigrationClient {
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub stub;

    public Mono<Long> migrate(WorkflowMutation c, List<IssueType> types, List<String> validKeys, Map<String, String> mapping) {
        return migrate(c, types, validKeys, mapping, List.of());
    }

    public Mono<Long> migrate(WorkflowMutation c, List<IssueType> types, List<String> validKeys,
                              Map<String, String> mapping, List<String> sourceKeys) {
        return Mono.deferContextual(context -> stub.withDeadlineAfter(30, TimeUnit.SECONDS).migrateWorkflowIssues(MigrateWorkflowIssuesRequest.newBuilder()
                        .setHeader(action.GrpcActionContext.enrich(Header.newBuilder().setRequestId(c.requestId()).setNodeId(c.nodeId()),
                                ru.taska.domain.ActionMetadata.current(context)))
                        .setProjectId(c.project().toString()).setActorUserId(c.actor().toString())
                        .setWorkflowId(c.workflow().toString()).addAllIssueTypeKeys(types.stream().map(IssueType::name).toList())
                        .addAllValidStatusKeys(validKeys).putAllStatusMappings(mapping).addAllSourceStatusKeys(sourceKeys).build())
                .map(response -> response.getMovedCount()));
    }
}
