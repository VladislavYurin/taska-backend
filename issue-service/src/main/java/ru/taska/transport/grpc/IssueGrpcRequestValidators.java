package ru.taska.transport.grpc;

import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.transport.grpc.dto.ValidatedIssueRequest;
import validator.GrpcRequestValidators;


public class IssueGrpcRequestValidators {
    private IssueGrpcRequestValidators() {
    }

    public static Mono<ValidatedIssueRequest> validateIssueScopedRequest(
            Header header, String issueIdRaw, String actorUserIdRaw
    ) {
        return Mono.zip(
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getRequestId(), "header.requestId"),
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getNodeId(), "header.nodeId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(issueIdRaw, "body.issueId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(actorUserIdRaw, "body.actorUserId")
        ).map(t -> new ValidatedIssueRequest(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }
}
