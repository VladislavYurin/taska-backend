package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AddIssueCommentRequest;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.AddIssueLabelRequest;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.AddIssueWorklogRequest;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalResponse;
import ru.taska.api.issue.v1.CreateIssueLinkRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueLinkRequest;
import ru.taska.api.issue.v1.DeleteIssueLinkResponse;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelResponse;
import ru.taska.api.issue.v1.GetIssueByKeyRequest;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.GetIssueWatchStateRequest;
import ru.taska.api.issue.v1.GetIssueWatchStateResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueCommentsRequest;
import ru.taska.api.issue.v1.ListIssueCommentsResponse;
import ru.taska.api.issue.v1.ListIssueLabelsRequest;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListIssueLinksRequest;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListIssueWatchersRequest;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.ListIssuesForBoardRequest;
import ru.taska.api.issue.v1.ListIssuesForBoardResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.RemoveIssueLabelRequest;
import ru.taska.api.issue.v1.RemoveIssueLabelResponse;
import ru.taska.api.issue.v1.SearchIssuesRequest;
import ru.taska.api.issue.v1.SearchIssuesResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UnwatchIssueRequest;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.api.issue.v1.UpdateIssueCommentRequest;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogRequest;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.api.issue.v1.UpdateProjectLabelRequest;
import ru.taska.api.issue.v1.WatchIssueRequest;
import ru.taska.api.issue.v1.WatchIssueResponse;

@GrpcService
@RequiredArgsConstructor
public class GrpcIssueServiceAdapter extends ReactorIssueServiceGrpc.IssueServiceImplBase{

    private final GrpcIssueService grpcIssueService;
    private final WorkflowIssueMigrationGrpcService workflowIssueMigration;
    private final GrpcIssueCommentService grpcIssueCommentService;
    private final GrpcIssueLinkService grpcIssueLinkService;
    private final GrpcIssueWatcherService grpcIssueWatcherService;
    private final GrpcIssueWorklogService grpcIssueWorklogService;

    @Override
    public Mono<CountProjectIssuesBatchInternalResponse> countProjectIssuesBatchInternal(
            Mono<CountProjectIssuesBatchInternalRequest> request) {
        return request.flatMap(req -> grpcIssueService.countProjectIssuesBatchInternal(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("countProjectIssuesBatchInternal"));
    }

    @Override
    public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.createIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("createIssue"));
    }

    @Override
    public Mono<IssueWithHistoryResponse> getIssue(Mono<GetIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.getIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("getIssue"));
    }

    @Override
    public Mono<GetIssueDetailsResponse> getIssueDetails(Mono<GetIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.getIssueDetails(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("getIssueDetails"));
    }

    @Override
    public Mono<IssueResponse> getIssueByKey(Mono<GetIssueByKeyRequest> request) {
        return request.flatMap(req -> grpcIssueService.getIssueByKey(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("getIssueByKey"));
    }

    @Override
    public Mono<ListIssuesResponse> listIssues(Mono<ListIssuesRequest> request) {
        return request.flatMap(req -> grpcIssueService.listIssues(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssues"));
    }

    @Override
    public Mono<IssueResponse> assignIssue(Mono<AssignIssueRequest> request){
        return request.flatMap(req -> grpcIssueService.assignIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("assignIssue"));
    }

    @Override
    public Mono<DeleteIssueResponse> deleteIssue(Mono<DeleteIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.deleteIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssue"));
    }

    @Override
    public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.updateIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssue"));
    }

    @Override
    public Mono<PatchIssueResponse> patchIssue(Mono<PatchIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.patchIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("patchIssue"));
    }

    @Override
    public Mono<IssueWithHistoryResponse> transitionIssue(Mono<TransitionIssueRequest> request) {
        return request.flatMap(req -> grpcIssueService.transitionIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("transitionIssue"));
    }

    @Override
    public Mono<ListIssueLinksResponse> listIssueLinks(Mono<ListIssueLinksRequest> request) {
        return request.flatMap(req -> grpcIssueLinkService.listIssueLinks(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueLinks"));    }

    @Override
    public Mono<IssueLinkResponse> createIssueLink(Mono<CreateIssueLinkRequest> request) {
        return request.flatMap(req -> grpcIssueLinkService.createIssueLink(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("createIssueLink"));
    }

    @Override
    public Mono<DeleteIssueLinkResponse> deleteIssueLink(Mono<DeleteIssueLinkRequest> request) {
        return request.flatMap(req -> grpcIssueLinkService.deleteIssueLink(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
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
        return request.flatMap(req -> grpcIssueCommentService.addIssueComment(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
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
        return request.flatMap(req -> grpcIssueCommentService.updateIssueComment(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
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
        return request.flatMap(req -> grpcIssueCommentService.deleteIssueComment(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
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
        return request.flatMap(req -> grpcIssueCommentService.listIssueComments(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }

    @Override
    public Mono<WatchIssueResponse> watchIssue(Mono<WatchIssueRequest> request) {
        return request.flatMap(req -> grpcIssueWatcherService.watchIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())));
    }

    @Override
    public Mono<UnwatchIssueResponse> unwatchIssue(Mono<UnwatchIssueRequest> request) {
        return request.flatMap(req -> grpcIssueWatcherService.unwatchIssue(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())));
    }

    @Override
    public Mono<ListIssueWatchersResponse> listIssueWatchers(Mono<ListIssueWatchersRequest> request) {
        return request.flatMap(req -> grpcIssueWatcherService.listIssueWatchers(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())));
    }

    @Override
    public Mono<GetIssueWatchStateResponse> getIssueWatchState(Mono<GetIssueWatchStateRequest> request) {
        return request.flatMap(req -> grpcIssueWatcherService.getIssueWatchState(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())));
    }

    @Override
    public Mono<ProjectLabelResponse> createProjectLabel(Mono<CreateProjectLabelRequest> request) {
        return request.flatMap(req -> grpcIssueService.createProjectLabel(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("createProjectLabel"));
    }

    @Override
    public Mono<ProjectLabelResponse> updateProjectLabel(Mono<UpdateProjectLabelRequest> request) {
        return request.flatMap(req -> grpcIssueService.updateProjectLabel(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("updateProjectLabel"));
    }

    @Override
    public Mono<DeleteProjectLabelResponse> deleteProjectLabel(Mono<DeleteProjectLabelRequest> request) {
        return request.flatMap(req -> grpcIssueService.deleteProjectLabel(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("deleteProjectLabel"));
    }

    @Override
    public Mono<ListProjectLabelsResponse> listProjectLabels(Mono<ListProjectLabelsRequest> request) {
        return request.flatMap(req -> grpcIssueService.listProjectLabels(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listProjectLabels"));
    }

    // ===== Методы для управления метками задачи (MEMBER) =====

    @Override
    public Mono<AddIssueLabelResponse> addIssueLabel(Mono<AddIssueLabelRequest> request) {
        return request.flatMap(req -> grpcIssueService.addIssueLabel(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueLabel"));
    }

    @Override
    public Mono<RemoveIssueLabelResponse> removeIssueLabel(Mono<RemoveIssueLabelRequest> request) {
        return request.flatMap(req -> grpcIssueService.removeIssueLabel(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("removeIssueLabel"));
    }

    @Override
    public Mono<ListIssueLabelsResponse> listIssueLabels(Mono<ListIssueLabelsRequest> request) {
        return request.flatMap(req -> grpcIssueService.listIssueLabels(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueLabels"));
    }

    /**
     * Поиск задач по ключу, summary и description с фильтрами
     */
    @Override
    public Mono<SearchIssuesResponse> searchIssues(Mono<SearchIssuesRequest> request) {
        return request.flatMap(req -> grpcIssueService.searchIssues(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("searchIssues"));
    }

    @Override
    public Mono<AddIssueWorklogResponse> addIssueWorklog( Mono<AddIssueWorklogRequest> request) {
        return request.flatMap(req -> grpcIssueWorklogService.addIssueWorklog(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueWorklog"));
    }

    @Override
    public Mono<UpdateIssueWorklogResponse> updateIssueWorklog(Mono<UpdateIssueWorklogRequest> request) {
        return request.flatMap(req -> grpcIssueWorklogService.updateIssueWorklog(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssueWorklog"));
    }

    @Override
    public Mono<ListIssueWorklogsResponse> listIssueWorklogs(Mono<ListIssueWorklogsRequest> request) {
        return request.flatMap(req -> grpcIssueWorklogService.listIssueWorklogs(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueWorklogs"));
    }

    @Override
    public Mono<DeleteIssueWorklogResponse> deleteIssueWorklog(Mono<DeleteIssueWorklogRequest> request) {
        return request.flatMap(req -> grpcIssueWorklogService.deleteIssueWorklog(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueWorklog"));
    }

    @Override
    public Mono<ListIssuesForBoardResponse> listIssuesForBoard(Mono<ListIssuesForBoardRequest> request){
        return request.flatMap(req -> grpcIssueService.listIssuesForBoard(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("ListIssuesForBoard"));
    }

    @Override
    public Mono<ru.taska.api.issue.v1.MigrateWorkflowIssuesResponse> migrateWorkflowIssues(
            Mono<ru.taska.api.issue.v1.MigrateWorkflowIssuesRequest> request) {
        return request.flatMap(req -> workflowIssueMigration.migrate(Mono.just(req))
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())))
                .transform(GrpcExceptionHandler.withErrorHandling("migrateWorkflowIssues"));
    }
}
