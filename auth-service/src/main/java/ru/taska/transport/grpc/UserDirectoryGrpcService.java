package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.directory.v1.ReactorUserDirectoryServiceGrpc;
import ru.taska.api.directory.v1.SearchUsersRequest;
import ru.taska.api.directory.v1.SearchUsersResponse;
import ru.taska.service.UserDirectoryService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class UserDirectoryGrpcService extends ReactorUserDirectoryServiceGrpc.UserDirectoryServiceImplBase {
    private final UserDirectoryService directory;

    @Override
    public Mono<SearchUsersResponse> searchUsers(Mono<SearchUsersRequest> request) {
        return request.flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId"))
                .flatMap(values -> directory.search(values.getT3(), req.getQuery(), req.getPage(), req.getPageSize())))
                .transform(GrpcExceptionHandler.withErrorHandling("SearchUsers"));
    }
}
