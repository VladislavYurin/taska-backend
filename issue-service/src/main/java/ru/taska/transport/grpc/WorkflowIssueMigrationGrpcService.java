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
        return request.flatMap(req -> {
            if(!req.hasExpectedWorkflowVersion())return Mono.error(new ru.taska.exception.DomainException(
                    ru.taska.exception.DomainStatus.FAILED_PRECONDITION,"Workflow service must send the locked source version",
                    java.util.Map.of("reason","WORKFLOW_VERSION_REQUIRED")));
            if(req.getExpectedWorkflowVersion()<1)return Mono.error(new ru.taska.exception.DomainException(
                    ru.taska.exception.DomainStatus.INVALID_ARGUMENT,"expectedWorkflowVersion must be positive"));
            return Mono.zip(
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(), "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getWorkflowId(), "workflowId"))
                .flatMap(ids -> service.migrate(ids.getT1(), ids.getT2(), ids.getT3(), req.getIssueTypeKeysList(),
                        req.getValidStatusKeysList(), req.getStatusMappingsMap(), req.getSourceStatusKeysList(),
                        req.getHeader().getRequestId(), req.getHeader().getNodeId(),req.getExpectedWorkflowVersion()))
                .map(count -> MigrateWorkflowIssuesResponse.newBuilder().setMovedCount(count).build());
        });
    }
}
