package ru.taska.transport.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.AddIssueWorklogRequest;
import ru.taska.api.issue.v1.AddIssueWorklogResponse;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.DeleteIssueWorklogResponse;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.UpdateIssueWorklogRequest;
import ru.taska.api.issue.v1.UpdateIssueWorklogResponse;
import ru.taska.api.issue.v1.WorklogResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.AddIssueWorklogRequestDto;
import ru.taska.domain.dto.UpdateIssueWorklogRequestDto;
import ru.taska.mapper.IssueWorklogMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcIssueWorklogServiceClient Tests")
class GrpcIssueWorklogServiceClientTest {

    private static final String REQUEST_ID = "req-id";
    private static final String NODE_ID = "api-gateway";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000002";
    private static final String ISSUE_ID = "00000000-0000-0000-0000-000000000003";
    private static final String WORKLOG_ID = "00000000-0000-0000-0000-000000000004";

    /** 2026-07-13T10:00:00Z */
    private static final Timestamp CREATED_AT = Timestamp.newBuilder().setSeconds(1_783_936_800L).build();
    /** 2026-07-13T11:00:00Z */
    private static final Timestamp UPDATED_AT = Timestamp.newBuilder().setSeconds(1_783_940_400L).build();

    @Mock
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service issueServiceProperties;

    private GrpcIssueWorklogServiceClient client;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        Mockito.when(properties.issueService()).thenReturn(issueServiceProperties);
        Mockito.when(issueServiceProperties.deadlineDuration()).thenReturn(Duration.ofMillis(5000));
        Mockito.when(issueServiceStub.withDeadlineAfter(
                        ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any(TimeUnit.class)
                ))
                .thenReturn(issueServiceStub);

        GatewayUserContext userContext = new GatewayUserContext(
                USER_ID,
                "testuser",
                "test@example.com",
                "Test User",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER
        );

        context = new GatewayContext(REQUEST_ID, NODE_ID, userContext);
        // IssueWorklogMapper без зависимостей, поэтому используем настоящий:
        // так тесты клиента заодно проверяют маппинг в обе стороны
        client = new GrpcIssueWorklogServiceClient(issueServiceStub, new IssueWorklogMapper(), properties);
    }

    // ============ addIssueWorklog ============

    @Test
    @DisplayName("addIssueWorklog должен собрать request с actor из GatewayContext и вернуть REST DTO")
    void addIssueWorklog_validRequest_buildsCorrectRequestAndReturnsResponse() {
        var requestDto = new AddIssueWorklogRequestDto(120, LocalDate.parse("2026-07-13"))
                .comment("Implemented validation");

        Mockito.when(issueServiceStub.addIssueWorklog(ArgumentMatchers.any(AddIssueWorklogRequest.class)))
                .thenReturn(Mono.just(AddIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().setComment("Implemented validation").build())
                        .build()));

        StepVerifier.create(client.addIssueWorklog(ISSUE_ID, Mono.just(requestDto), context))
                .assertNext(result -> {
                    Assertions.assertThat(result.getId()).isEqualTo(UUID.fromString(WORKLOG_ID));
                    Assertions.assertThat(result.getIssueId()).isEqualTo(UUID.fromString(ISSUE_ID));
                    Assertions.assertThat(result.getProjectId()).isEqualTo(UUID.fromString(PROJECT_ID));
                    Assertions.assertThat(result.getAuthorUserId()).isEqualTo(UUID.fromString(USER_ID));
                    Assertions.assertThat(result.getSpentMinutes()).isEqualTo(120);
                    Assertions.assertThat(result.getWorkDate()).isEqualTo(LocalDate.parse("2026-07-13"));
                    Assertions.assertThat(result.getComment()).isEqualTo("Implemented validation");
                    Assertions.assertThat(result.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T10:00:00Z"));
                    Assertions.assertThat(result.getUpdatedAt()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<AddIssueWorklogRequest> captor = ArgumentCaptor.forClass(AddIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).addIssueWorklog(captor.capture());

        AddIssueWorklogRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getSpentMinutes()).isEqualTo(120);
        Assertions.assertThat(request.getBody().getWorkDate()).isEqualTo("2026-07-13");
        Assertions.assertThat(request.getBody().hasComment()).isTrue();
        Assertions.assertThat(request.getBody().getComment()).isEqualTo("Implemented validation");

        Mockito.verify(issueServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("addIssueWorklog не должен передавать comment, если он не указан, и вернуть comment = null")
    void addIssueWorklog_withoutComment_doesNotSetComment() {
        var requestDto = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"));

        Mockito.when(issueServiceStub.addIssueWorklog(ArgumentMatchers.any(AddIssueWorklogRequest.class)))
                .thenReturn(Mono.just(AddIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().build())
                        .build()));

        StepVerifier.create(client.addIssueWorklog(ISSUE_ID, Mono.just(requestDto), context))
                .assertNext(result -> Assertions.assertThat(result.getComment()).isNull())
                .verifyComplete();

        ArgumentCaptor<AddIssueWorklogRequest> captor = ArgumentCaptor.forClass(AddIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).addIssueWorklog(captor.capture());

        Assertions.assertThat(captor.getValue().getBody().hasComment()).isFalse();
    }

    @Test
    @DisplayName("addIssueWorklog не должен передавать пустой comment")
    void addIssueWorklog_blankComment_doesNotSetComment() {
        var requestDto = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"))
                .comment("   ");

        Mockito.when(issueServiceStub.addIssueWorklog(ArgumentMatchers.any(AddIssueWorklogRequest.class)))
                .thenReturn(Mono.just(AddIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().build())
                        .build()));

        StepVerifier.create(client.addIssueWorklog(ISSUE_ID, Mono.just(requestDto), context))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AddIssueWorklogRequest> captor = ArgumentCaptor.forClass(AddIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).addIssueWorklog(captor.capture());

        Assertions.assertThat(captor.getValue().getBody().hasComment()).isFalse();
    }

    @Test
    @DisplayName("addIssueWorklog должен пробросить ошибку, если issue-service вернул NOT_FOUND")
    void addIssueWorklog_issueNotFound_propagatesError() {
        var requestDto = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"));

        Mockito.when(issueServiceStub.addIssueWorklog(ArgumentMatchers.any(AddIssueWorklogRequest.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.withDescription("issue not found").asRuntimeException()));

        StepVerifier.create(client.addIssueWorklog(ISSUE_ID, Mono.just(requestDto), context))
                .expectErrorMatches(error -> hasStatus(error, Status.Code.NOT_FOUND))
                .verify();
    }

    // ============ updateIssueWorklog ============

    @Test
    @DisplayName("updateIssueWorklog должен передать только указанные поля и вернуть обновлённый worklog")
    void updateIssueWorklog_partialRequest_setsOnlyProvidedFields() {
        var requestDto = new UpdateIssueWorklogRequestDto();
        requestDto.setSpentMinutes(90);

        Mockito.when(issueServiceStub.updateIssueWorklog(ArgumentMatchers.any(UpdateIssueWorklogRequest.class)))
                .thenReturn(Mono.just(UpdateIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().setSpentMinutes(90).setUpdatedAt(UPDATED_AT).build())
                        .build()));

        StepVerifier.create(client.updateIssueWorklog(ISSUE_ID, WORKLOG_ID, Mono.just(requestDto), context))
                .assertNext(result -> {
                    Assertions.assertThat(result.getSpentMinutes()).isEqualTo(90);
                    Assertions.assertThat(result.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T11:00:00Z"));
                })
                .verifyComplete();

        ArgumentCaptor<UpdateIssueWorklogRequest> captor = ArgumentCaptor.forClass(UpdateIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).updateIssueWorklog(captor.capture());

        UpdateIssueWorklogRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getWorklogId()).isEqualTo(WORKLOG_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().hasSpentMinutes()).isTrue();
        Assertions.assertThat(request.getBody().getSpentMinutes()).isEqualTo(90);
        Assertions.assertThat(request.getBody().hasWorkDate()).isFalse();
        Assertions.assertThat(request.getBody().hasComment()).isFalse();

        Mockito.verify(issueServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("updateIssueWorklog должен передать все поля, если они указаны")
    void updateIssueWorklog_fullRequest_setsAllFields() {
        var requestDto = new UpdateIssueWorklogRequestDto();
        requestDto.setSpentMinutes(45);
        requestDto.setWorkDate(LocalDate.parse("2026-07-14"));
        requestDto.setComment("Fixed review comments");

        Mockito.when(issueServiceStub.updateIssueWorklog(ArgumentMatchers.any(UpdateIssueWorklogRequest.class)))
                .thenReturn(Mono.just(UpdateIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().build())
                        .build()));

        StepVerifier.create(client.updateIssueWorklog(ISSUE_ID, WORKLOG_ID, Mono.just(requestDto), context))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<UpdateIssueWorklogRequest> captor = ArgumentCaptor.forClass(UpdateIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).updateIssueWorklog(captor.capture());

        var body = captor.getValue().getBody();

        Assertions.assertThat(body.getSpentMinutes()).isEqualTo(45);
        Assertions.assertThat(body.getWorkDate()).isEqualTo("2026-07-14");
        Assertions.assertThat(body.getComment()).isEqualTo("Fixed review comments");
    }

    @Test
    @DisplayName("updateIssueWorklog должен пробросить ошибку, если issue-service вернул PERMISSION_DENIED")
    void updateIssueWorklog_permissionDenied_propagatesError() {
        var requestDto = new UpdateIssueWorklogRequestDto();
        requestDto.setSpentMinutes(30);

        Mockito.when(issueServiceStub.updateIssueWorklog(ArgumentMatchers.any(UpdateIssueWorklogRequest.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        StepVerifier.create(client.updateIssueWorklog(ISSUE_ID, WORKLOG_ID, Mono.just(requestDto), context))
                .expectErrorMatches(error -> hasStatus(error, Status.Code.PERMISSION_DENIED))
                .verify();
    }

    // ============ deleteIssueWorklog ============

    @Test
    @DisplayName("deleteIssueWorklog должен собрать request с actor из GatewayContext и завершиться без значения")
    void deleteIssueWorklog_validParams_buildsCorrectRequestAndCompletes() {
        Mockito.when(issueServiceStub.deleteIssueWorklog(ArgumentMatchers.any(DeleteIssueWorklogRequest.class)))
                .thenReturn(Mono.just(DeleteIssueWorklogResponse.newBuilder()
                        .setWorklog(worklog().build())
                        .build()));

        StepVerifier.create(client.deleteIssueWorklog(ISSUE_ID, WORKLOG_ID, context))
                .verifyComplete();

        ArgumentCaptor<DeleteIssueWorklogRequest> captor = ArgumentCaptor.forClass(DeleteIssueWorklogRequest.class);
        Mockito.verify(issueServiceStub).deleteIssueWorklog(captor.capture());

        DeleteIssueWorklogRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getWorklogId()).isEqualTo(WORKLOG_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("deleteIssueWorklog должен пробросить ошибку, если issue-service вернул DEADLINE_EXCEEDED")
    void deleteIssueWorklog_deadlineExceeded_propagatesError() {
        Mockito.when(issueServiceStub.deleteIssueWorklog(ArgumentMatchers.any(DeleteIssueWorklogRequest.class)))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.asRuntimeException()));

        StepVerifier.create(client.deleteIssueWorklog(ISSUE_ID, WORKLOG_ID, context))
                .expectErrorMatches(error -> hasStatus(error, Status.Code.DEADLINE_EXCEEDED))
                .verify();
    }

    // ============ listIssueWorklogs ============

    @Test
    @DisplayName("listIssueWorklogs должен собрать request и вернуть список worklogs в items")
    void listIssueWorklogs_validParams_buildsCorrectRequestAndReturnsItems() {
        String secondWorklogId = UUID.randomUUID().toString();

        Mockito.when(issueServiceStub.listIssueWorklogs(ArgumentMatchers.any(ListIssueWorklogsRequest.class)))
                .thenReturn(Mono.just(ListIssueWorklogsResponse.newBuilder()
                        .addWorklogs(worklog().build())
                        .addWorklogs(worklog().setId(secondWorklogId).setSpentMinutes(30).build())
                        .build()));

        StepVerifier.create(client.listIssueWorklogs(ISSUE_ID, context))
                .assertNext(result -> {
                    Assertions.assertThat(result.getItems()).hasSize(2);
                    Assertions.assertThat(result.getItems().get(0).getId()).isEqualTo(UUID.fromString(WORKLOG_ID));
                    Assertions.assertThat(result.getItems().get(1).getId()).isEqualTo(UUID.fromString(secondWorklogId));
                    Assertions.assertThat(result.getItems().get(1).getSpentMinutes()).isEqualTo(30);
                })
                .verifyComplete();

        ArgumentCaptor<ListIssueWorklogsRequest> captor = ArgumentCaptor.forClass(ListIssueWorklogsRequest.class);
        Mockito.verify(issueServiceStub).listIssueWorklogs(captor.capture());

        ListIssueWorklogsRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("listIssueWorklogs должен вернуть пустой items, если worklogs нет")
    void listIssueWorklogs_noWorklogs_returnsEmptyItems() {
        Mockito.when(issueServiceStub.listIssueWorklogs(ArgumentMatchers.any(ListIssueWorklogsRequest.class)))
                .thenReturn(Mono.just(ListIssueWorklogsResponse.newBuilder().build()));

        StepVerifier.create(client.listIssueWorklogs(ISSUE_ID, context))
                .assertNext(result -> Assertions.assertThat(result.getItems()).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("listIssueWorklogs должен пробросить ошибку, если issue-service вернул UNAVAILABLE")
    void listIssueWorklogs_downstreamUnavailable_propagatesError() {
        Mockito.when(issueServiceStub.listIssueWorklogs(ArgumentMatchers.any(ListIssueWorklogsRequest.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));

        StepVerifier.create(client.listIssueWorklogs(ISSUE_ID, context))
                .expectErrorMatches(error -> hasStatus(error, Status.Code.UNAVAILABLE))
                .verify();
    }

    // ============ helpers ============

    private WorklogResponse.Builder worklog() {
        return WorklogResponse.newBuilder()
                .setId(WORKLOG_ID)
                .setIssueId(ISSUE_ID)
                .setProjectId(PROJECT_ID)
                .setAuthorUserId(USER_ID)
                .setSpentMinutes(120)
                .setWorkDate("2026-07-13")
                .setCreatedAt(CREATED_AT)
                .setVersion(1);
    }

    private boolean hasStatus(Throwable error, Status.Code code) {
        return error instanceof StatusRuntimeException statusError
                && statusError.getStatus().getCode() == code;
    }
}