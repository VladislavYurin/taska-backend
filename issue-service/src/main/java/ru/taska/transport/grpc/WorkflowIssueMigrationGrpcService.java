package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest;
import ru.taska.api.issue.v1.MigrateWorkflowIssuesResponse;
import ru.taska.service.WorkflowIssueMigrationService;
import validator.GrpcRequestValidators;

@Service
@RequiredArgsConstructor
public class WorkflowIssueMigrationGrpcService {
    private final WorkflowIssueMigrationService service;

    public Mono<MigrateWorkflowIssuesResponse> migrate(Mono<MigrateWorkflowIssuesRequest> request) {
        return request.flatMap(req -> Mono.zip(
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(), "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getWorkflowId(), "workflowId"))
                .flatMap(ids -> service.migrate(ids.getT1(), ids.getT2(), ids.getT3(), req.getIssueTypeKeysList(),
                        req.getValidStatusKeysList(), req.getStatusMappingsMap(), req.getSourceStatusKeysList(),
                        req.getHeader().getRequestId(), req.getHeader().getNodeId()))
                .map(count -> MigrateWorkflowIssuesResponse.newBuilder().setMovedCount(count).build()));
    }
}
