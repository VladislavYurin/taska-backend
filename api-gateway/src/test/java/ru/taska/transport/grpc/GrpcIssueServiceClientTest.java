package ru.taska.transport.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueLinkRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequestBody;
import ru.taska.api.issue.v1.DeleteIssueLinkRequest;
import ru.taska.api.issue.v1.DeleteIssueLinkResponse;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssueLinkResponse;
import ru.taska.api.issue.v1.IssueLinkType;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueLinksRequest;
import ru.taska.api.issue.v1.ListIssueLinksResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequestBody;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.AssignIssueRequestDto;
import ru.taska.domain.dto.CreateIssueLinkRequestDto;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueLinkResponseDto;
import ru.taska.domain.dto.IssueLinkTypeDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssueLinksResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.TransitionIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import ru.taska.mapper.IssueMapper;

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
    private static final String LINK_ID = "00000000-0000-0000-0000-000000000007";
    private static final String LABEL_ID = "00000000-0000-0000-0000-000000000007";
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

        Mockito.when(issueMapper.toRestListIssuesResponseDto(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.listIssues(PROJECT_ID, STATUS_KEY, ASSIGNEE_ID, page, pageSize, LABEL_ID, context))
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
                .toRestListIssuesResponseDto(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC listIssues без фильтров")
    void listIssues_shouldCallStubWithoutFilters() {
        var grpcResponse = ListIssuesResponse.getDefaultInstance();
        var restResponse = new ListIssuesResponseDto();

        Mockito.when(stub.listIssues(Mockito.any(ListIssuesRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestListIssuesResponseDto(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.listIssues(PROJECT_ID, null, null, null, null, null, context))
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
                .toRestListIssuesResponseDto(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC createIssue и вернуть ответ")
    void createIssue_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new CreateIssueRequestDto("TASK", SUMMARY, DESCRIPTION, "MEDIUM");

        var grpcRequest = CreateIssueRequest.newBuilder()
                                            .setHeader(
                                                    Header.newBuilder()
                                                          .setRequestId(REQUEST_ID)
                                                          .setNodeId(NODE_ID)
                                                          .build()
                                            )
                                            .setBody(
                                                    CreateIssueRequestBody.newBuilder()
                                                                          .setIdempotencyKey(IDEMPOTENCY_KEY)
                                                                          .setProjectId(PROJECT_ID)
                                                                          .setIssueType(IssueType.ISSUE_TYPE_TASK)
                                                                          .setSummary(SUMMARY)
                                                                          .setDescription(DESCRIPTION)
                                                                          .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                                                          .setReporterId(USER_ID)
                                                                          .build()
                                            )
                                            .build();

        var grpcResponse = IssueResponse.getDefaultInstance();
        var restResponse = new IssueResponseDto();

        Mockito.when(issueMapper.toCreateIssueGrpcRequest(PROJECT_ID, IDEMPOTENCY_KEY, restRequest, context))
               .thenReturn(grpcRequest);

        Mockito.when(stub.createIssue(grpcRequest))
               .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestIssueResponse(grpcResponse))
               .thenReturn(restResponse);

        StepVerifier.create(client.createIssue(PROJECT_ID, IDEMPOTENCY_KEY, Mono.just(restRequest), context))
                    .expectNext(restResponse)
                    .verifyComplete();

        Mockito.verify(stub, Mockito.times(1)).createIssue(grpcRequest);
        Mockito.verify(issueMapper, Mockito.times(1))
               .toCreateIssueGrpcRequest(PROJECT_ID, IDEMPOTENCY_KEY, restRequest, context);
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
        var restRequest = new UpdateIssueRequestDto(SUMMARY, DESCRIPTION, "MEDIUM");

        var grpcRequest = UpdateIssueRequest.newBuilder()
                                            .setHeader(
                                                    Header.newBuilder()
                                                          .setRequestId(REQUEST_ID)
                                                          .setNodeId(NODE_ID)
                                                          .build()
                                            )
                                            .setBody(
                                                    UpdateIssueRequestBody.newBuilder()
                                                                          .setIssueId(ISSUE_ID)
                                                                          .setActorUserId(USER_ID)
                                                                          .setSummary(SUMMARY)
                                                                          .setDescription(DESCRIPTION)
                                                                          .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM)
                                                                          .build()
                                            )
                                            .build();

        var grpcResponse = UpdateIssueResponse.getDefaultInstance();
        var restResponse = new UpdateIssueResponseDto();

        Mockito.when(issueMapper.toUpdateIssueRequest(ISSUE_ID, restRequest, context))
               .thenReturn(grpcRequest);

        Mockito.when(stub.updateIssue(grpcRequest))
               .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestUpdateResponse(grpcResponse))
               .thenReturn(restResponse);

        StepVerifier.create(client.updateIssue(ISSUE_ID, Mono.just(restRequest), context))
                    .expectNext(restResponse)
                    .verifyComplete();

        Mockito.verify(stub, Mockito.times(1)).updateIssue(grpcRequest);
        Mockito.verify(issueMapper, Mockito.times(1))
               .toUpdateIssueRequest(ISSUE_ID, restRequest, context);
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

    @Test
    @DisplayName("Должен вызвать gRPC listIssueLinks и вернуть ответ")
    void listIssueLinks_shouldCallStubAndReturnMappedResponse() {
        var grpcResponse = ListIssueLinksResponse.getDefaultInstance();
        var restResponse = new ListIssueLinksResponseDto();

        Mockito.when(stub.listIssueLinks(Mockito.any(ListIssueLinksRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toRestListIssueLinkResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.listIssueLinks(ISSUE_ID, context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(ListIssueLinksRequest.class);

        Mockito.verify(stub).listIssueLinks(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueMapper).toRestListIssueLinkResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC createIssueLink и вернуть ответ")
    void createIssueLink_shouldCallStubAndReturnMappedResponse() {
        var restRequest = new CreateIssueLinkRequestDto("00000000-0000-0000-0000-000000000006", IssueLinkTypeDto.RELATES_TO);
        var grpcResponse = IssueLinkResponse.getDefaultInstance();
        var restResponse = new IssueLinkResponseDto();

        Mockito.when(stub.createIssueLink(Mockito.any(CreateIssueLinkRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(issueMapper.toGrpcIssueLinkType(Mockito.any(IssueLinkTypeDto.class)))
                .thenReturn(IssueLinkType.ISSUE_LINK_TYPE_RELATES_TO);

        Mockito.when(issueMapper.toRestIssueLinkResponse(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.createIssueLink(ISSUE_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(CreateIssueLinkRequest.class);

        Mockito.verify(stub).createIssueLink(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getSourceIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getTargetIssueId()).isEqualTo("00000000-0000-0000-0000-000000000006");
        Assertions.assertThat(request.getBody().getLinkType()).isEqualTo(IssueLinkType.ISSUE_LINK_TYPE_RELATES_TO);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);

        Mockito.verify(issueMapper).toRestIssueLinkResponse(grpcResponse);
    }

    @Test
    @DisplayName("Должен вызвать gRPC deleteIssueLink и вернуть ответ")
    void deleteIssueLink_shouldCallStubAndReturnMappedResponse() {
        var grpcResponse = DeleteIssueLinkResponse.getDefaultInstance();

        Mockito.when(stub.deleteIssueLink(Mockito.any(DeleteIssueLinkRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        StepVerifier.create(client.deleteIssueLink(ISSUE_ID, LINK_ID, context))
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(DeleteIssueLinkRequest.class);

        Mockito.verify(stub).deleteIssueLink(captor.capture());

        var request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getIssueId()).isEqualTo(ISSUE_ID);
        Assertions.assertThat(request.getBody().getLinkId()).isEqualTo(LINK_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
    }
}