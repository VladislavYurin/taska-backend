package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;

@Configuration
public class ProjectServiceGrpcConfig {

    @Value("${grpc.client.project-service.host}")
    private String host;

    @Value("${grpc.client.project-service.port}")
    private int port;

    @Bean
    public ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        return ReactorProjectServiceGrpc.newReactorStub(channel);
    }
}
