package client;

import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ProjectIssueTypesRequest;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ProjectIssueTypes {
    /** Shape of a type written before TAS-432 and the default of a new one. */
    public static final String DEFAULT_SHAPE = "square";

    private ProjectIssueTypes() { }

    /**
     * Writes the look of a registry entry (TAS-432) for both reader generations: {@code shape} and the
     * optional {@code icon} for new readers, and an always-valid {@code legacy_icon} for readers built
     * before TAS-432, which require one of the eight icons.
     */
    public static ProjectIssueTypeResponse.Builder writeLook(ProjectIssueTypeResponse.Builder builder,
                                                             String code, String shape, String icon) {
        builder.setShape(shape).setLegacyIcon(icon != null ? icon : legacyIcon(code));
        if (icon != null) builder.setIcon(icon);
        return builder;
    }

    /** Icon of a registry entry, or null when the type has none. A pre-TAS-432 writer only fills legacy_icon. */
    public static String icon(ProjectIssueTypeResponse type) {
        if (type.hasIcon()) return type.getIcon();
        if (type.hasShape() || type.getLegacyIcon().isEmpty()) return null;
        return type.getLegacyIcon();
    }

    /** Shape of a registry entry; a pre-TAS-432 writer has none, and its types are squares. */
    public static String shape(ProjectIssueTypeResponse type) {
        return type.hasShape() ? type.getShape() : DEFAULT_SHAPE;
    }

    static String legacyIcon(String code) {
        return switch (code) {
            case "BUG" -> "bug";
            case "STORY" -> "book-open";
            default -> "check-square";
        };
    }

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
