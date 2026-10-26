package client;

import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypesRequest;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ProjectIssueTypes {
    private ProjectIssueTypes() { }

    public static Mono<List<IssueType>> list(ReactorProjectServiceGrpc.ReactorProjectServiceStub stub,
                                            String requestId, String nodeId, UUID project, UUID actor) {
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS).listProjectIssueTypes(ProjectIssueTypesRequest.newBuilder()
                        .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId))
                        .setProjectId(project.toString()).setActorUserId(actor.toString()).build())
                .map(response -> response.getItemsList().stream().map(t -> IssueType.valueOf(t.getCode())).toList());
    }

    public static Mono<Void> require(List<IssueType> allowed, IssueType key) {
        return allowed.contains(key) ? Mono.empty() : Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                "Issue type " + key + " is not defined in this project"));
    }
}
