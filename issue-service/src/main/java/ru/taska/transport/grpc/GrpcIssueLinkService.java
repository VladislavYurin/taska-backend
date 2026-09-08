package ru.taska.transport.grpc;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.CreateIssueLinkRequest;
import ru.taska.api.issue.v1.DeleteIssueLinkRequest;
import ru.taska.api.issue.v1.DeleteIssueLinkResponse;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.ListIssueLinksRequest;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.domain.IssueLinkType;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.link.IssueLinkService;
import ru.taska.transport.grpc.logging.GrpcIssueLogging;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueLinkService {

    private final IssueLinkService issueLinkService;
    private final IssueMapper issueMapper;

    @TrackMetrics(counter = "issue-service_list-issue-links_grpc_counter",
            timer = "issue-service_list-issue-links_grpc_timer")
    public Mono<ListIssueLinksResponse> listIssueLinks(Mono<ListIssueLinksRequest> request) {
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
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logOnError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listIssueLinks"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] listIssueLinks: issueId={}, actorUserId={}", requestId, nodeId, issueId, actorUserId);

                            return issueLinkService.listIssueLinks(requestId, nodeId, issueId, actorUserId)
                                    .map(link -> issueMapper.toIssueLinkProto(link, issueId))
                                    .collectList()
                                    .map(links ->
                                            ListIssueLinksResponse.newBuilder()
                                                    .addAllIssueLinks(links)
                                                    .build()
                                    )
                                    .doOnNext(response ->
                                            log.info("[{}][{}] listIssueLinks: successfully found {} links for issue, issueId={}",
                                                    requestId, nodeId, response.getIssueLinksCount(), issueId)
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "listIssueLinks")
                                    );
                        })
                );
    }

    @TrackMetrics(counter = "issue-service_create-issue-link_grpc_counter",
            timer = "issue-service_create-issue-link_grpc_timer")
    public Mono<IssueLinkResponse> createIssueLink(Mono<CreateIssueLinkRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getSourceIssueId(), "body.sourceIssueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getTargetIssueId(), "body.targetIssueId"
                                ),
                                GrpcRequestValidators.requireSpecifiedOrInvalidArgument(
                                        req.getBody().getLinkType(), "body.linkType"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logOnError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "createIssueLink"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID sourceIssueId = t.getT3();
                            UUID targetIssueId = t.getT4();
                            IssueLinkType linkType = issueMapper.toDomainIssueLinkType(t.getT5());
                            UUID actorUserId = t.getT6();

                            log.info("[{}][{}] createIssueLink: sourceIssueId={}, targetIssueId={}, linkType={}, actorUserId={}",
                                    requestId, nodeId, sourceIssueId, targetIssueId, linkType, actorUserId);

                            return issueLinkService.createIssueLink(requestId, nodeId, sourceIssueId, targetIssueId, linkType, actorUserId)
                                    .doOnNext(issueLink ->
                                            log.info("[{}][{}] createIssueLink: successfully created, id={}",
                                                    requestId, nodeId, issueLink.getId())
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "createIssueLink")
                                    );
                        })
                )
                .map(link -> issueMapper.toIssueLinkProto(link, link.getSourceIssueId()));
    }

    @TrackMetrics(counter = "issue-service_delete-issue-link_grpc_counter",
            timer = "issue-service_delete-issue-link_grpc_timer")
    public Mono<DeleteIssueLinkResponse> deleteIssueLink(Mono<DeleteIssueLinkRequest> request) {
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
                                        req.getBody().getLinkId(), "body.linkId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logOnError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "deleteIssueLink"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID linkId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] deleteIssueLink: issueId={}, linkId={}, actorUserId={}",
                                    requestId, nodeId, issueId, linkId, actorUserId);

                            return issueLinkService.deleteIssueLink(requestId, nodeId, issueId, linkId, actorUserId)
                                    .doOnNext(issueLink ->
                                            log.info("[{}][{}] deleteIssueLink: successfully deleted, id={}",
                                                    requestId, nodeId, issueLink.getId())
                                    )
                                    .doOnError(DomainException.class,
                                            GrpcIssueLogging.logOnError(requestId, nodeId, "deleteIssueLink")
                                    );
                        })
                )
                .map(issueMapper::toDeleteIssueLinkProto);
    }
}
