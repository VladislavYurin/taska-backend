package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
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
    public ManagedChannel notificationManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.notificationService().host(),
                        properties.notificationService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub() {
        return ReactorAuthServiceGrpc.newReactorStub(authManagedChannel());
    }
    @Bean
    public ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub() {
        return ReactorNotificationServiceGrpc.newReactorStub(notificationManagedChannel());
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
}
