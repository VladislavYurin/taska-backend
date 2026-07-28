package ru.taska.transport.grpc;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.AssignIssueRequestDto;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.TransitionIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import ru.taska.mapper.IssueMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class GrpcIssueServiceClientTest {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "api-gateway-service";
    private static final String ISSUE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000003";
    private static final String ASSIGNEE_ID = "00000000-0000-0000-0000-000000000004";
    private static final String IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000005";
    private static final String TRANSITION_ID = "00000000-0000-0000-0000-000000000006";
    public static final String SUMMARY = "Summary-1";
    public static final String DESCRIPTION = "Description-1";
    public static final String STATUS_KEY = "TODO";

    @Mock
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub stub;

    @Mock
    private IssueMapper issueMapper;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service issueProperties;

    @InjectMocks
    private GrpcIssueServiceClient client;

    private GatewayContext context;

    @BeforeEach
    void setUp() {
        context = new GatewayContext(
                REQUEST_ID,
                NODE_ID,
                GatewayUserContext.builder()
                        .userId(USER_ID)
                        .build()
        );

        Mockito.when(properties.issueService())
                .thenReturn(issueProperties);

        Mockito.when(issueProperties.deadlineDuration())
                .thenReturn(Duration.ofMillis(100));

        Mockito.when(stub.withDeadlineAfter(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
                .thenReturn(stub);
    }

    @Test
    @DisplayName("Должен вызвать gRPC getIssue и вернуть ответ")
    void getIssue_shouldCallStubAndReturnMappedResponse() {
        var grpcResponse = IssueWithHistoryResponse.getDefaultInstance();
        var restResponse = new IssueWithHistoryResponseDto();

        Mockito.when(stub.getIssue(Mockito.any(GetIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueWithHistoryResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.getIssue(ISSUE_ID, context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(GetIssueRequest.class);

        Mockito.verify(stub).getIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueWithHistoryResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC listIssues со всеми заполненными фильтрами")
    void listIssues_shouldCallStubWithAllFilters() {
        var grpcResponse = ListIssuesResponse.getDefaultInstance();
        var restResponse = new ListIssuesResponseDto();

        int page = 0;
        int pageSize = 3;

        Mockito.when(stub.listIssues(Mockito.any(ListIssuesRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestListIssuesRequest(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.listIssues(PROJECT_ID, STATUS_KEY, ASSIGNEE_ID, page, pageSize, context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(ListIssuesRequest.class);

        Mockito.verify(stub).listIssues(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getStatusKey()).isEqualTo(STATUS_KEY);
        Assertions.assertThat(request.getBody().getAssigneeId()).isEqualTo(ASSIGNEE_ID);
        Assertions.assertThat(request.getBody().getPage()).isEqualTo(page);
        Assertions.assertThat(request.getBody().getPageSize()).isEqualTo(pageSize);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestListIssuesRequest(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC listIssues без фильтров")
    void listIssues_shouldCallStubWithoutFilters() {
        var grpcResponse = ListIssuesResponse.getDefaultInstance();
        var restResponse = new ListIssuesResponseDto();

        Mockito.when(stub.listIssues(Mockito.any(ListIssuesRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestListIssuesRequest(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.listIssues(PROJECT_ID, null, null, null, null, context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(ListIssuesRequest.class);

        Mockito.verify(stub).listIssues(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().hasStatusKey()).isFalse();
        Assertions.assertThat(request.getBody().hasAssigneeId()).isFalse();
        Assertions.assertThat(request.getBody().hasPage()).isFalse();
        Assertions.assertThat(request.getBody().hasPageSize()).isFalse();

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestListIssuesRequest(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC createIssue и вернуть ответ")
    void createIssue_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new CreateIssueRequestDto("TASK", SUMMARY, "MEDIUM");
        var grpcResponse = IssueResponse.getDefaultInstance();
        var restResponse = new IssueResponseDto();

        Mockito.when(stub.createIssue(Mockito.any(CreateIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toGrpcIssueType("TASK"))
                .thenReturn(IssueType.ISSUE_TYPE_TASK);

        Mockito.when(issueMapper.toGrpcIssuePriority("MEDIUM"))
                .thenReturn(IssuePriority.ISSUE_PRIORITY_MEDIUM);

        Mockito.when(issueMapper.toRestIssueResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.createIssue(PROJECT_ID, IDEMPOTENCY_KEY, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(CreateIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).createIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getIssueType()).isEqualTo(IssueType.ISSUE_TYPE_TASK);
        Assertions.assertThat(request.getBody().getSummary()).isEqualTo(SUMMARY);
        Assertions.assertThat(request.getBody().getPriority()).isEqualTo(IssuePriority.ISSUE_PRIORITY_MEDIUM);
        Assertions.assertThat(request.getBody().getReporterId()).isEqualTo(USER_ID);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC assignIssue и вернуть ответ")
    void assignIssue_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new AssignIssueRequestDto(ASSIGNEE_ID);
        var grpcResponse = IssueResponse.getDefaultInstance();
        var restResponse = new IssueResponseDto();

        Mockito.when(stub.assignIssue(Mockito.any(AssignIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.assignIssue(ISSUE_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(AssignIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).assignIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getAssigneeId()).isEqualTo(ASSIGNEE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC updateIssue и вернуть ответ")
    void updateIssue_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new UpdateIssueRequestDto();
        restRequest.setSummary(SUMMARY);
        restRequest.setDescription(DESCRIPTION);
        restRequest.setPriority("MEDIUM");
        var grpcResponse = UpdateIssueResponse.getDefaultInstance();
        var restResponse = new UpdateIssueResponseDto();

        Mockito.when(stub.updateIssue(Mockito.any(UpdateIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toGrpcIssuePriority("MEDIUM"))
                .thenReturn(IssuePriority.ISSUE_PRIORITY_MEDIUM);

        Mockito.when(issueMapper.toRestUpdateResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.updateIssue(ISSUE_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var capture = ArgumentCaptor.forClass(UpdateIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).updateIssue(capture.capture());

        var request = capture.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getSummary()).isEqualTo(SUMMARY);
        Assertions.assertThat(request.getBody().getDescription()).isEqualTo(DESCRIPTION);
        Assertions.assertThat(request.getBody().getPriority()).isEqualTo(IssuePriority.ISSUE_PRIORITY_MEDIUM);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestUpdateResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC transitionIssue и вернуть ответ")
    void transitionIssue_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new TransitionIssueRequestDto();
        Object restPayload = new Object();
        restRequest.setPayload(restPayload);

        var stringPayload = "payload";
        var grpcResponse = IssueWithHistoryResponse.getDefaultInstance();
        var restResponse = new IssueWithHistoryResponseDto();

        Mockito.when(issueMapper.convertPayloadToJsonString(Mockito.any()))
                .thenReturn(stringPayload);

        Mockito.when(stub.transitionIssue(Mockito.any(TransitionIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueWithHistoryResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.transitionIssue(ISSUE_ID, TRANSITION_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransitionIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).transitionIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getTransitionId()).isEqualTo(TRANSITION_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getPayload()).isEqualTo(stringPayload);

        Mockito.verify(issueMapper, Mockito.times(1))
                .convertPayloadToJsonString(restPayload);

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueWithHistoryResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC transitionIssue без payload")
    void transitionIssue_shouldCallWithoutPayload() {
        var restRequest = new TransitionIssueRequestDto();
        var grpcResponse = IssueWithHistoryResponse.getDefaultInstance();
        var restResponse = new IssueWithHistoryResponseDto();

        Mockito.when(stub.transitionIssue(Mockito.any(TransitionIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueWithHistoryResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.transitionIssue(ISSUE_ID, TRANSITION_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransitionIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).transitionIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getTransitionId()).isEqualTo(TRANSITION_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getPayload()).isEmpty();

        Mockito.verify(issueMapper, Mockito.never())
                .convertPayloadToJsonString(Mockito.any());

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueWithHistoryResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC transitionIssue, если request пустой")
    void transitionIssue_shouldCallStub_whenRequestMonoIsEmpty() {
        var grpcResponse = IssueWithHistoryResponse.getDefaultInstance();
        var restResponse = new IssueWithHistoryResponseDto();

        Mockito.when(stub.transitionIssue(Mockito.any(TransitionIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueWithHistoryResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.transitionIssue(ISSUE_ID, TRANSITION_ID, Mono.empty(), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(TransitionIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).transitionIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getTransitionId()).isEqualTo(TRANSITION_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getPayload()).isEmpty();

        Mockito.verify(issueMapper, Mockito.never())
                .convertPayloadToJsonString(Mockito.any());

        Mockito.verify(issueMapper, Mockito.times(1))
                .toRestIssueWithHistoryResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC deleteIssue и успешно завершиться")
    void deleteIssue_shouldCallStubAndReturnEmpty() {
        var grpcResponse = DeleteIssueResponse.getDefaultInstance();

        Mockito.when(stub.deleteIssue(Mockito.any(DeleteIssueRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        StepVerifier.create(client.deleteIssue(ISSUE_ID, context))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(DeleteIssueRequest.class);

        Mockito.verify(stub, Mockito.times(1)).deleteIssue(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
    }
}