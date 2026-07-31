package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.api.issue.v1.AddIssueCommentRequest;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.UpdateIssueCommentRequest;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueCommentRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.ListIssueCommentsRequest;
import ru.taska.api.issue.v1.ListIssueCommentsResponse;

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
    // --------------------------------- Методы для комментариев к issue --------------------
    /**
     * Добавляет комментарий к задаче.
     *
     * @param request Mono с запросом {@link AddIssueCommentRequest}
     * @return Mono с ответом {@link AddIssueCommentResponse}
     */
    @Override
    public Mono<AddIssueCommentResponse> addIssueComment(Mono<AddIssueCommentRequest> request) {
        return grpcIssueService.addIssueComment(request)
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueComment"));
    }

    /**
     * Обновляет комментарий к задаче.
     *
     * @param request Mono с запросом {@link UpdateIssueCommentRequest}
     * @return Mono с ответом {@link UpdateIssueCommentResponse}
     */
    @Override
    public Mono<UpdateIssueCommentResponse> updateIssueComment(Mono<UpdateIssueCommentRequest> request) {
        return grpcIssueService.updateIssueComment(request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssueComment"));
    }

    /**
     * Удаляет комментарий к задаче (мягкое удаление).
     *
     * @param request Mono с запросом {@link DeleteIssueCommentRequest}
     * @return Mono с ответом {@link DeleteIssueCommentResponse}
     */
    @Override
    public Mono<DeleteIssueCommentResponse> deleteIssueComment(Mono<DeleteIssueCommentRequest> request) {
        return grpcIssueService.deleteIssueComment(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueComment"));
    }

    /**
     * Возвращает список комментариев к задаче.
     *
     * @param request Mono с запросом {@link ListIssueCommentsRequest}
     * @return Mono с ответом {@link ListIssueCommentsResponse}
     */
    @Override
    public Mono<ListIssueCommentsResponse> listIssueComments(Mono<ListIssueCommentsRequest> request) {
        return grpcIssueService.listIssueComments(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }
}
