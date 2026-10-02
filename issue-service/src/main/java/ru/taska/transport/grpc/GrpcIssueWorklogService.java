package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.AddIssueWorklogRequest;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.UpdateIssueWorklogRequest;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.exception.DomainException;
import ru.taska.mapper.WorklogMapper;
import ru.taska.service.worklog.WorklogService;
import validator.GrpcRequestValidators;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;


@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueWorklogService {
    private final WorklogService worklogService;
    private final WorklogMapper worklogMapper;

    @TrackMetrics(counter = "issue-service_add-issue-worklog_grpc_counter",
            timer = "issue-service_add-issue-worklog_grpc_timer")
    public Mono<AddIssueWorklogResponse> addIssueWorklog(
            Mono<AddIssueWorklogRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                GrpcRequestValidators.requirePositiveOrInvalidArgument(
                                        req.getBody().getSpentMinutes(), "body.spentMinutes"
                                ),
                                GrpcRequestValidators.parseLocalDateOrInvalidArgument(
                                        req.getBody().getWorkDate(),
                                        "body.workDate"
                                )
                        ).doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "addIssueWorklog"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();
                            int spentMinutes = t.getT5();
                            LocalDate workDate = t.getT6();

                            String rawComment = req.getBody().hasComment() ? req.getBody().getComment() : null;
                            String comment = (rawComment != null && !rawComment.isBlank()) ? rawComment.trim() : null;

                            log.info("[{}][{}] addIssueWorklog: issueId={}, spentMinutes={}, workDate={}",
                                    requestId, nodeId, issueId, spentMinutes, workDate);

                            CreateWorklogDto createWorklogDto = new CreateWorklogDto(
                                    spentMinutes,
                                    workDate,
                                    comment
                            );

                            return worklogService.addIssueWorklog(requestId,
                                    nodeId,
                                    issueId,
                                    actorUserId,
                                    createWorklogDto
                            ).doOnNext(worklog ->
                                    log.info("[{}][{}] addIssueWorklog: successfully created, worklogId={}",
                                            requestId, nodeId, worklog.getId())
                            ).doOnError(DomainException.class,
                                    logOnError(requestId, nodeId, "addIssueWorklog")
                            );
                        })
                        .map(worklogMapper::toAddResponse));
    }

    @TrackMetrics(counter = "issue-service_update-issue-worklog_grpc_counter",
            timer = "issue-service_update-issue-worklog_grpc_timer")
    public Mono<UpdateIssueWorklogResponse> updateIssueWorklog(
            Mono<UpdateIssueWorklogRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"
                                        ),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"
                                        ),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getIssueId(), "body.issueId"
                                        ),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getWorklogId(), "body.worklogId"
                                        ),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId"
                                        ),
                                        GrpcRequestValidators.requireOptionalPositiveOrInvalidArgument(
                                                req.getBody().hasSpentMinutes(), req.getBody().getSpentMinutes(), "body.spentMinutes"
                                        ),
                                        GrpcRequestValidators.parseOptionalLocalDateOrInvalidArgument(
                                                req.getBody().hasWorkDate(), req.getBody().getWorkDate(), "body.workDate"
                                        )
                        ).doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "updateIssueWorklog"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID worklogId = t.getT4();
                            UUID actorUserId = t.getT5();
                            Integer spentMinutes = t.getT6().orElse(null);
                            LocalDate workDate = t.getT7().orElse(null);

                            String rawComment = req.getBody().hasComment() ? req.getBody().getComment() : null;
                            String comment = (rawComment != null && !rawComment.isBlank()) ? rawComment.trim() : null;

                            log.info("[{}][{}] updateIssueWorklog: issueId={}, spentMinutes={}, workDate={}",
                                    requestId, nodeId, issueId, spentMinutes, workDate);

                            UpdateWorklogDto updateWorklogDto = new UpdateWorklogDto(
                                    spentMinutes,
                                    workDate,
                                    comment
                            );

                            return worklogService.updateIssueWorklog(requestId,
                                    nodeId,
                                    issueId,
                                    worklogId,
                                    actorUserId,
                                    updateWorklogDto
                            ).doOnNext(worklog ->
                                    log.info("[{}][{}] updateWorklogDto: successfully updated, worklogId={}",
                                            requestId, nodeId, worklog.getId())
                            ).doOnError(DomainException.class,
                                    logOnError(requestId, nodeId, "updateWorklogDto")
                            );
                        })
                        .map(worklogMapper::toUpdateResponse));
    }

    @TrackMetrics(counter = "issue-service_list-issue-worklogs_grpc_counter",
            timer = "issue-service_list-issue-worklogs_grpc_timer")
    public Mono<ListIssueWorklogsResponse> listIssueWorklogs(
            Mono<ListIssueWorklogsRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                )
                        ).doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "listIssueWorklog"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] listIssueWorklog: issueId={}",
                                    requestId, nodeId, issueId);

                            return worklogService.listIssueWorklog(requestId,
                                    nodeId,
                                    issueId,
                                    actorUserId
                            ).doOnNext(worklogs ->
                                    log.info("[{}][{}] listIssueWorklog: found {} worklogs",
                                            requestId, nodeId, worklogs.size())
                            ).doOnError(DomainException.class,
                                    logOnError(requestId, nodeId, "listIssueWorklog")
                            );
                        })
                        .map(worklogMapper::toListResponse));
    }

    @TrackMetrics(counter = "issue-service_delete-issue-worklog_grpc_counter",
            timer = "issue-service_delete-issue-worklog_grpc_timer")
    public Mono<DeleteIssueWorklogResponse> deleteIssueWorklog(
            Mono<DeleteIssueWorklogRequest> request
    ) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getWorklogId(), "body.worklogId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                )
                        ).doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "deleteIssueWorklog"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID worklogId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] deleteIssueWorklog: issueId={}",
                                    requestId, nodeId, issueId);

                            return worklogService.deleteIssueWorklog(requestId,
                                    nodeId,
                                    issueId,
                                    worklogId,
                                    actorUserId
                            ).doOnNext(worklog ->
                                    log.info("[{}][{}] deleteWorklogDto: successfully deleted, worklogId={}",
                                            requestId, nodeId, worklog.getId())
                            ).doOnError(DomainException.class,
                                    logOnError(requestId, nodeId, "deleteWorklogDto")
                            );
                        })
                        .map(worklogMapper::toDeleteResponse));
    }


    private Consumer<Throwable> logValidationError(Header header, String operation) {
        return throwable -> {
            if (throwable instanceof StatusRuntimeException e
                    && e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                log.error("[{}][{}] {} validation error: {}",
                        header.getRequestId(), header.getNodeId(), operation, e.getStatus().getDescription());
            }
        };
    }

    private Consumer<Throwable> logOnError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof DomainException e) {
                log.error("[{}][{}] {} failed: status={}, message={}",
                        requestId, nodeId, operation, e.getStatus(), e.getMessage());
            }
        };
    }
}
