package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;

@Configuration
@RequiredArgsConstructor
public class GrpcClientConfig {
    private final GrpcClientProperties properties;

    @Bean
    public ManagedChannel authManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.authService().host(),
                        properties.authService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub() {
        return ReactorProfileServiceGrpc.newReactorStub(authManagedChannel());
    }

    @Bean
    public ManagedChannel issueManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.issueService().host(),
                        properties.issueService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub() {
        return ReactorIssueServiceGrpc.newReactorStub(issueManagedChannel());
    }

    @Bean
    public ManagedChannel workFlowManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.workFlowService().host(),
                        properties.workFlowService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub() {
        return ReactorWorkflowServiceGrpc.newReactorStub(workFlowManagedChannel());
    }
}
