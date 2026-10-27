package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.CheckProjectWorkflowScopeRequest;
import ru.taska.service.WorkflowScopeCheckService;
import validator.GrpcRequestValidators;

@Service
@RequiredArgsConstructor
public class WorkflowScopeCheckGrpcService {
    private final WorkflowScopeCheckService service;
    public Mono<Empty> check(Mono<CheckProjectWorkflowScopeRequest> request) {
        return request.flatMap(req -> Mono.zip(GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(),"projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(),"actorUserId"))
                .flatMap(ids -> service.check(req.getHeader(),ids.getT1(),ids.getT2(),req.getIssueTypeKeysList(),
                        req.hasExpectedWorkflowId()?req.getExpectedWorkflowId():null,
                        req.hasExpectedWorkflowVersion()?req.getExpectedWorkflowVersion():null,
                        req.hasStatusKey()?req.getStatusKey():null))).thenReturn(Empty.getDefaultInstance());
    }
}
