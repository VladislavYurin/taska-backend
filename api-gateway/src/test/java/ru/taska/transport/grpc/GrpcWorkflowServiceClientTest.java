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
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.mapper.WorkflowMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
class GrpcWorkflowServiceClientTest {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "api-gateway-service";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String USER_ID = "00000000-0000-0000-0000-000000000002";
    private static final IssueTypeDto ISSUE_TYPE_DTO = IssueTypeDto.BUG;

    @Mock
    private ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub stub;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service workflowProperties;

    @InjectMocks
    private GrpcWorkflowServiceClient client;

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

        Mockito.when(properties.workflowService())
                .thenReturn(workflowProperties);

        Mockito.when(workflowProperties.deadlineDuration())
                .thenReturn(Duration.ofMillis(100));

        Mockito.when(stub.withDeadlineAfter(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
                .thenReturn(stub);
    }

    @Test
    @DisplayName("Должен вызвать gRPC getWorkflowForProject и вернуть ответ")
    void getWorkflowForProject_shouldCallStubAndReturnMappedResponse() {
        var grpcRequest = GetWorkflowForProjectRequest.getDefaultInstance();
        var grpcResponse = WorkflowResponse.getDefaultInstance();
        var restResponse = new WorkflowResponseDto();

        Mockito.when(workflowMapper.toGetWorkflowGrpcRequest(PROJECT_ID, ISSUE_TYPE_DTO, context))
                .thenReturn(grpcRequest);

        Mockito.when(stub.getWorkflowForProject(grpcRequest))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(workflowMapper.toWorkflowResponseDto(grpcResponse))
                .thenReturn(restResponse);

        StepVerifier.create(client.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE_DTO, context))
                .expectNext(restResponse)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(GetWorkflowForProjectRequest.class);

        Mockito.verify(stub, Mockito.times(1)).getWorkflowForProject(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request).isEqualTo(grpcRequest);

        Mockito.verify(workflowMapper, Mockito.times(1))
                .toGetWorkflowGrpcRequest(PROJECT_ID, ISSUE_TYPE_DTO, context);

        Mockito.verify(workflowMapper, Mockito.times(1))
                .toWorkflowResponseDto(grpcResponse);
    }

    @Test
    @DisplayName("Должен пробросить ошибку, если gRPC вызов завершился с ошибкой")
    void getWorkflowForProject_shouldPropagateError_whenGrpcCallFails() {
        var grpcRequest = GetWorkflowForProjectRequest.getDefaultInstance();
        var expectedException = new RuntimeException("gRPC error");

        Mockito.when(workflowMapper.toGetWorkflowGrpcRequest(PROJECT_ID, ISSUE_TYPE_DTO, context))
                .thenReturn(grpcRequest);

        Mockito.when(stub.getWorkflowForProject(grpcRequest))
                .thenReturn(Mono.error(expectedException));

        StepVerifier.create(client.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE_DTO, context))
                .expectErrorMatches(throwable -> throwable.equals(expectedException))
                .verify();

        Mockito.verify(stub, Mockito.times(1)).getWorkflowForProject(grpcRequest);

        Mockito.verify(workflowMapper, Mockito.never())
                .toWorkflowResponseDto(Mockito.any());
    }
}