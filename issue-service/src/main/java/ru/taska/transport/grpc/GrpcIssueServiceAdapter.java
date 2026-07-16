package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.*;

@GrpcService
@RequiredArgsConstructor
public class GrpcIssueServiceAdapter extends ReactorIssueServiceGrpc.IssueServiceImplBase{

    private final GrpcIssueService grpcIssueService;

    @Override
    public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> request) {
        return grpcIssueService.createIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createIssue"));
    }

    @Override
    public Mono<IssueWithHistoryResponse> getIssue(Mono<GetIssueRequest> request) {
        return grpcIssueService.getIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getIssue"));
    }

    @Override
    public Mono<ListIssuesResponse> listIssues(Mono<ListIssuesRequest> request) {
        return grpcIssueService.listIssues(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listIssues"));
    }

    @Override
    public Mono<IssueResponse> assignIssue(Mono<AssignIssueRequest> request){
        return grpcIssueService.assignIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("assignIssue"));
    }

    @Override
    public Mono<DeleteIssueResponse> deleteIssue(Mono<DeleteIssueRequest> request) {
        return grpcIssueService.deleteIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssue"));
    }

    @Override
    public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> request) {
        return grpcIssueService.updateIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssue"));
    }
    @Override
    public Mono<IssueWithHistoryResponse> transitionIssue(Mono<TransitionIssueRequest> request) {
        return grpcIssueService.transitionIssue(request)
                .transform(GrpcExceptionHandler.withErrorHandling("transitionIssue"));
    }
}
