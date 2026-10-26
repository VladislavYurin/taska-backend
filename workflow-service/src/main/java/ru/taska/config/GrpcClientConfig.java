package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;

@Configuration
@RequiredArgsConstructor
public class GrpcClientConfig {

    private final GrpcClientProperties properties;

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel workflowIssueManagedChannel(@Value("${ISSUE_GRPC_HOST:localhost}") String host,
                                                     @Value("${ISSUE_GRPC_PORT:9094}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }

    @Bean
    public ReactorIssueServiceGrpc.ReactorIssueServiceStub workflowIssueServiceStub(
            @org.springframework.beans.factory.annotation.Qualifier("workflowIssueManagedChannel") ManagedChannel channel) {
        return ReactorIssueServiceGrpc.newReactorStub(channel);
    }

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
}
