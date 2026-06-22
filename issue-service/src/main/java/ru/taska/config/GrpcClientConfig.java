package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
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
}
