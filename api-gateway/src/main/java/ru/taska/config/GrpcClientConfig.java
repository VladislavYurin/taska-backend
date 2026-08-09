package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
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
    public ManagedChannel adminManagedChannel() {
        return ManagedChannelBuilder
                .forAddress(
                        properties.adminService().host(),
                        properties.adminService().port()
                )
                .usePlaintext()
                .build();
    }

    @Bean
    public ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub() {
        return ReactorNotificationServiceGrpc.newReactorStub(notificationManagedChannel());
    }

    @Bean
    public ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub() {
        return ReactorWorkflowServiceGrpc.newReactorStub(workflowManagedChannel());
    }

    @Bean
    public ReactorAuthServiceGrpc.ReactorAuthServiceStub authServiceStub() {
        return ReactorAuthServiceGrpc.newReactorStub(authManagedChannel());
    }

    @Bean
    public ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub() {
        return ReactorIssueServiceGrpc.newReactorStub(issueManagedChannel());
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
    @Bean
    public ReactorAdminServiceGrpc.ReactorAdminServiceStub adminServiceStub() {
        return ReactorAdminServiceGrpc.newReactorStub(adminManagedChannel());
    }
}
