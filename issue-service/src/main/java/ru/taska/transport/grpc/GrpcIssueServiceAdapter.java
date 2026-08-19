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
    private final GrpcIssueCommentService grpcIssueCommentService;
    private final GrpcIssueLinkService grpcIssueLinkService;
    private final GrpcIssueWatcherService grpcIssueWatcherService;

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

    @Override
    public Mono<ListIssueLinksResponse> listIssueLinks(Mono<ListIssueLinksRequest> request) {
        return grpcIssueLinkService.listIssueLinks(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueLinks"));    }

    @Override
    public Mono<IssueLinkResponse> createIssueLink(Mono<CreateIssueLinkRequest> request) {
        return grpcIssueLinkService.createIssueLink(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createIssueLink"));
    }

    @Override
    public Mono<DeleteIssueLinkResponse> deleteIssueLink(Mono<DeleteIssueLinkRequest> request) {
        return grpcIssueLinkService.deleteIssueLink(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueLink"));
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
        return grpcIssueCommentService.addIssueComment(request)
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
        return grpcIssueCommentService.updateIssueComment(request)
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
        return grpcIssueCommentService.deleteIssueComment(request)
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
        return grpcIssueCommentService.listIssueComments(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }

    @Override
    public Mono<WatchIssueResponse> watchIssue(Mono<WatchIssueRequest> request) {
        return grpcIssueWatcherService.watchIssue(request);
    }

    @Override
    public Mono<UnwatchIssueResponse> unwatchIssue(Mono<UnwatchIssueRequest> request) {
        return grpcIssueWatcherService.unwatchIssue(request);
    }

    @Override
    public Mono<ListIssueWatchersResponse> listIssueWatchers(Mono<ListIssueWatchersRequest> request) {
        return grpcIssueWatcherService.listIssueWatchers(request);
    }

    @Override
    public Mono<GetIssueWatchStateResponse> getIssueWatchState(Mono<GetIssueWatchStateRequest> request) {
        return grpcIssueWatcherService.getIssueWatchState(request);
    }

    public Mono<ProjectLabelResponse> createProjectLabel(Mono<CreateProjectLabelRequest> request) {
        return grpcIssueService.createProjectLabel(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createProjectLabel"));
    }

    @Override
    public Mono<ProjectLabelResponse> updateProjectLabel(Mono<UpdateProjectLabelRequest> request) {
        return grpcIssueService.updateProjectLabel(request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateProjectLabel"));
    }

    @Override
    public Mono<DeleteProjectLabelResponse> deleteProjectLabel(Mono<DeleteProjectLabelRequest> request) {
        return grpcIssueService.deleteProjectLabel(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteProjectLabel"));
    }

    @Override
    public Mono<ListProjectLabelsResponse> listProjectLabels(Mono<ListProjectLabelsRequest> request) {
        return grpcIssueService.listProjectLabels(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listProjectLabels"));
    }

    // ===== Методы для управления метками задачи (MEMBER) =====

    @Override
    public Mono<AddIssueLabelResponse> addIssueLabel(Mono<AddIssueLabelRequest> request) {
        return grpcIssueService.addIssueLabel(request)
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueLabel"));
    }

    @Override
    public Mono<RemoveIssueLabelResponse> removeIssueLabel(Mono<RemoveIssueLabelRequest> request) {
        return grpcIssueService.removeIssueLabel(request)
                .transform(GrpcExceptionHandler.withErrorHandling("removeIssueLabel"));
    }

    @Override
    public Mono<ListIssueLabelsResponse> listIssueLabels(Mono<ListIssueLabelsRequest> request) {
        return grpcIssueService.listIssueLabels(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueLabels"));
    }

    /**
     * Поиск задач по ключу, summary и description с фильтрами
     */
    @Override
    public Mono<SearchIssuesResponse> searchIssues(Mono<SearchIssuesRequest> request) {
        return grpcIssueService.searchIssues(request)
                .transform(GrpcExceptionHandler.withErrorHandling("searchIssues"));
    }

    @Override
    public Mono<ListIssuesForBoardResponse> listIssuesForBoard(Mono<ListIssuesForBoardRequest> request){
        return grpcIssueService.listIssuesForBoard(request)
                .transform(GrpcExceptionHandler.withErrorHandling("ListIssuesForBoard"));
    }
}
