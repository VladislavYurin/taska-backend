package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;

@Configuration
@RequiredArgsConstructor
public class GrpcClientConfig {

    private final GrpcClientProperties properties;

    @Bean
    public ManagedChannel projectManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.projectService().host(),
                        properties.projectService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub() {
        return ReactorProjectServiceGrpc.newReactorStub(projectManagedChannel());
    }

    @Bean
    public ManagedChannel workflowManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.workflowService().host(),
                        properties.workflowService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub() {
        return ReactorWorkflowServiceGrpc.newReactorStub(workflowManagedChannel());
    }
}
