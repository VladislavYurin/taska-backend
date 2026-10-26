package ru.taska.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.*;
import ru.taska.domain.ActionChannel;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.entity.IssueHistory;
import ru.taska.mapper.CommentMapper;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueCommentRepository;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.transport.grpc.GrpcIssueServiceAdapter;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.transport.kafka.OutboxEventScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Real PostgreSQL + production gRPC handlers, histories, comments and outbox. */
class ActionAttributionIntegrationTest extends AbstractIT {
    @Autowired org.springframework.r2dbc.core.DatabaseClient databaseClient;
    @Autowired GrpcIssueServiceAdapter grpc;
    @Autowired IssueCommentRepository comments;
    @Autowired IssueHistoryRepository history;
    @Autowired IssueRepository issues;
    @Autowired IssueHistoryService historyService;
    @Autowired OutboxEventService outbox;
    @Autowired IssueMapper issueMapper;
    @Autowired CommentMapper commentMapper;
    @MockitoBean ProjectRoleChecker roles;
    @MockitoBean OutboxEventScheduler scheduler;
    private UUID project, issue, actor;
    private static final UUID TOKEN = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        project = UUID.randomUUID(); issue = UUID.randomUUID(); actor = UUID.randomUUID();
        databaseClient.sql("""
                INSERT INTO taska.issues (id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)
                VALUES (:id,:project,1,:key,'TASK','Attribution','TODO','MEDIUM',:actor)
                """).bind("id", issue).bind("project", project).bind("key", "ATTR-" + issue).bind("actor", actor).then().block();
        when(roles.checkProjectWriteRole(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @ParameterizedTest
    @EnumSource(ActionChannel.class)
    void threeChannelsPersistThroughGrpcButPublicResponsesNeverContainTokenIdentity(ActionChannel channel) {
        var response = add(channel, channel.name()).block();
        UUID id = UUID.fromString(response.getComment().getId());
        var stored = comments.findById(id).block();
        assertThat(stored.getChannel()).isEqualTo(channel.name());
        assertThat(stored.getTokenId()).isEqualTo(channel == ActionChannel.UI ? null : TOKEN);
        assertThat(response.getComment().getChannel()).isEqualTo(channel.name());
        assertThat(response.toString()).doesNotContain(TOKEN.toString(), "token_id", "token_name");
        var event = history.findByIssueIdOrderByOccurredAtDesc(issue, org.springframework.data.domain.Limit.of(10)).blockFirst();
        assertThat(event.getChannel()).isEqualTo(channel.name());
        assertThat(event.getTokenId()).isEqualTo(stored.getTokenId());
        assertThat(issueMapper.toIssueHistoryProto(event).getChannel()).isEqualTo(channel.name());
        assertThat(issueMapper.toIssueHistoryProto(event).toString()).doesNotContain(TOKEN.toString(), "token_id", "token_name");
        assertOutboxChannels(List.of(channel.name()));
    }

    @Test
    void latestCommentWriteUpdatesItsChannelWhileHistoryRetainsEachAction() {
        var created = add(ActionChannel.MCP, "Agent text").block().getComment();
        var updated = grpc.updateIssueComment(Mono.just(UpdateIssueCommentRequest.newBuilder()
                .setHeader(header(ActionChannel.UI)).setBody(UpdateIssueCommentRequestBody.newBuilder()
                        .setIssueId(issue.toString()).setCommentId(created.getId()).setActorUserId(actor.toString()).setBody("Human edit"))
                .build())).block().getComment();
        assertThat(updated.getChannel()).isEqualTo("UI");
        var stored = comments.findById(UUID.fromString(created.getId())).block();
        assertThat(stored.getTokenId()).isNull();
        assertThat(stored.getVersion()).isEqualTo(2);
        var events = history.findByIssueIdOrderByOccurredAtDesc(issue, org.springframework.data.domain.Limit.of(10)).collectList().block();
        assertThat(events).extracting(IssueHistory::getChannel).containsExactly("UI", "MCP");
        assertThat(events.get(0).getTokenId()).isNull();
        assertThat(events.get(1).getTokenId()).isEqualTo(TOKEN);
        assertOutboxChannels(List.of("MCP", "UI"));
    }

    @Test
    void concurrentRequestsDoNotShareAttributionThroughReactorThreads() {
        var responses = Flux.fromArray(ActionChannel.values()).flatMap(channel -> add(channel, channel.name()), 3).collectList().block();
        assertThat(responses).hasSize(3);
        for (var response : responses) {
            var comment = response.getComment();
            assertThat(comment.getChannel()).isEqualTo(comment.getBody());
            var stored = comments.findById(UUID.fromString(comment.getId())).block();
            assertThat(stored.getChannel()).isEqualTo(comment.getBody());
            assertThat(stored.getTokenId()).isEqualTo(comment.getChannel().equals("UI") ? null : TOKEN);
        }
        assertOutboxChannels(List.of("UI", "API_TOKEN", "MCP"));
    }

    @Test
    void legacyHeaderAndRowsRemainUnknownAndNullable() {
        var response = grpc.addIssueComment(Mono.just(AddIssueCommentRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("old-client").setNodeId("old-gateway"))
                .setBody(AddIssueCommentRequestBody.newBuilder().setIssueId(issue.toString()).setAuthorUserId(actor.toString()).setBody("Old client"))
                .build())).block();
        assertThat(response.getComment().hasChannel()).isFalse();
        var stored = comments.findById(UUID.fromString(response.getComment().getId())).block();
        assertThat(stored.getChannel()).isNull();
        assertThat(stored.getTokenId()).isNull();
        assertThat(commentMapper.toCommentProto(stored).hasChannel()).isFalse();
        var old = history.save(IssueHistory.builder().issueId(issue).eventType(ru.taska.domain.entity.IssueEventType.UPDATED)
                .actorUserId(actor).occurredAt(Instant.now()).payload(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()).build()).block();
        var reread = history.findById(old.getId()).block();
        assertThat(reread.getChannel()).isNull();
        assertThat(issueMapper.toIssueHistoryProto(reread).hasChannel()).isFalse();
    }

    @Test
    void issueCreationHistoryAndOutboxUseTheSameRequestContext() {
        var entity = issues.findById(issue).block();
        var metadata = new ActionMetadata(ActionChannel.API_TOKEN, TOKEN);
        historyService.saveIssueCreateHistory("create", "gateway", entity)
                .contextWrite(context -> context.put(ActionMetadata.class, metadata)).block();
        outbox.saveOutboxEvent("create", "gateway", ru.taska.event.AggregateType.ISSUE, entity)
                .contextWrite(context -> context.put(ActionMetadata.class, metadata)).block();
        var event = history.findByIssueIdOrderByOccurredAtDesc(issue, org.springframework.data.domain.Limit.of(10)).blockFirst();
        assertThat(event.getChannel()).isEqualTo("API_TOKEN");
        assertThat(event.getTokenId()).isEqualTo(TOKEN);
        assertOutboxChannels(List.of("API_TOKEN"));
    }

    @Test
    void invalidInternalHeaderFailsBeforeAnyMutation() {
        var request = AddIssueCommentRequest.newBuilder().setHeader(Header.newBuilder().setRequestId("bad").setNodeId("gateway")
                        .setActionChannel("MCP")) // A new PAT/MCP attribution must include its private audit id.
                .setBody(AddIssueCommentRequestBody.newBuilder().setIssueId(issue.toString()).setAuthorUserId(actor.toString()).setBody("Rejected"))
                .build();
        StepVerifier.create(grpc.addIssueComment(Mono.just(request))).expectErrorSatisfies(error ->
                assertThat(((io.grpc.StatusRuntimeException) error).getStatus().getCode()).isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT)).verify();
        assertThat(comments.countActiveByIssueId(issue).block()).isZero();
    }

    private Mono<AddIssueCommentResponse> add(ActionChannel channel, String body) {
        return grpc.addIssueComment(Mono.just(AddIssueCommentRequest.newBuilder().setHeader(header(channel))
                .setBody(AddIssueCommentRequestBody.newBuilder().setIssueId(issue.toString()).setAuthorUserId(actor.toString()).setBody(body))
                .build()));
    }

    private Header header(ActionChannel channel) {
        var builder = Header.newBuilder().setRequestId(UUID.randomUUID().toString()).setNodeId("api-gateway").setActionChannel(channel.name());
        if (channel != ActionChannel.UI) builder.setTokenId(TOKEN.toString());
        return builder.build();
    }

    private void assertOutboxChannels(List<String> expected) {
        var rows = databaseClient.sql("SELECT payload::text AS payload, payload->>'channel' AS channel FROM taska.outbox_events WHERE aggregate_id=:id")
                .bind("id", issue).fetch().all().collectList().block();
        assertThat(rows).extracting(row -> row.get("channel")).containsExactlyInAnyOrderElementsOf(expected);
        rows.forEach(row -> assertThat((String) row.get("payload")).doesNotContain(TOKEN.toString(), "tokenId", "token_id", "tokenName"));
    }
}
