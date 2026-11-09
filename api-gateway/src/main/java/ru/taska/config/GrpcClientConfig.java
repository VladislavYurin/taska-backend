package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.admin.v1.ReactorAdminServiceGrpc;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.issue.attachment.v1.ReactorIssueAttachmentServiceGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;

@org.springframework.context.annotation.Import(ru.taska.security.tenant.TrustedRpcTlsConfiguration.class)
@Configuration
@RequiredArgsConstructor
public class GrpcClientConfig {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.TrustedRpcChannels> trustedChannels;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ru.taska.security.tenant.RpcContextSecurity> rpcSecurity;
    @org.springframework.beans.factory.annotation.Value("${taska.rpc.context.enabled:false}")
    private boolean trustedEnabled;
    private io.grpc.ManagedChannel trustedChannel(String host,int port,String audience) {
      if(!trustedEnabled)return io.grpc.ManagedChannelBuilder.forAddress(host,port).usePlaintext().build();
      var factory=trustedChannels.getIfAvailable();var security=rpcSecurity.getIfAvailable();
      if(factory==null || security==null)throw new IllegalStateException("Trusted RPC channel/security must be provisioned");
      var raw=factory.create(host,port,audience);
      return new ru.taska.security.tenant.InterceptedManagedChannel(raw,new ru.taska.security.tenant.LegacyRpcClientInterceptor(security));
    }



    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="taska.rpc.context.enabled",havingValue="true")
    public ru.taska.api.b2b.v1.ReactorGatewayCredentialServiceGrpc.ReactorGatewayCredentialServiceStub gatewayCredentialStub(){return ru.taska.api.b2b.v1.ReactorGatewayCredentialServiceGrpc.newReactorStub(authManagedChannel());}

    @Bean
    public ru.taska.api.oauth.v1.ReactorOAuthServiceGrpc.ReactorOAuthServiceStub oauthServiceStub() {
        return ru.taska.api.oauth.v1.ReactorOAuthServiceGrpc.newReactorStub(authManagedChannel());
    }


    private final GrpcClientProperties properties;

    @Bean
    public ManagedChannel authManagedChannel() {
        return trustedChannel(properties.authService().host(),properties.authService().port(),"auth-service");
    }

    @Bean
    public ManagedChannel notificationManagedChannel() {
        return trustedChannel(properties.notificationService().host(),properties.notificationService().port(),"notification-service");
    }

    @Bean
    public ManagedChannel workflowManagedChannel() {
        return trustedChannel(properties.workflowService().host(),properties.workflowService().port(),"workflow-service");
    }

    @Bean
    public ManagedChannel issueManagedChannel() {
        return trustedChannel(properties.issueService().host(),properties.issueService().port(),"issue-service");
    }

    @Bean
    public ManagedChannel projectManagedChannel() {
        return trustedChannel(properties.projectService().host(),properties.projectService().port(),"project-service");
    }

    @Bean
    public ManagedChannel adminManagedChannel() {
        return trustedChannel(properties.adminService().host(),properties.adminService().port(),"admin-service");
    }

    @Bean
    public ReactorIssueAttachmentServiceGrpc.ReactorIssueAttachmentServiceStub issueAttachmentServiceStub() {
        return ReactorIssueAttachmentServiceGrpc.newReactorStub(issueManagedChannel());
    }

    @Bean
    public ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub() {
        return ReactorNotificationServiceGrpc.newReactorStub(notificationManagedChannel());
    }

    @Bean
    public ru.taska.api.notification.v1.ReactorNotificationSettingsServiceGrpc.ReactorNotificationSettingsServiceStub notificationSettingsStub() {
        return ru.taska.api.notification.v1.ReactorNotificationSettingsServiceGrpc.newReactorStub(notificationManagedChannel());
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
    public ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub() {
        return ReactorProjectServiceGrpc.newReactorStub(projectManagedChannel());
    }
    @Bean
    public ReactorAdminServiceGrpc.ReactorAdminServiceStub adminServiceStub() {
        return ReactorAdminServiceGrpc.newReactorStub(adminManagedChannel());
    }

    @Bean
    public ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub() {
        return ReactorProfileServiceGrpc.newReactorStub(authManagedChannel());
    }
    @Bean
    public ru.taska.api.directory.v1.ReactorUserDirectoryServiceGrpc.ReactorUserDirectoryServiceStub userDirectoryStub() {
        return ru.taska.api.directory.v1.ReactorUserDirectoryServiceGrpc.newReactorStub(authManagedChannel());
    }

    @Bean
    public ru.taska.api.auth.admin.inviteuser.v1.ReactorAdminInviteUserServiceGrpc.ReactorAdminInviteUserServiceStub inviteUserStub() {
        return ru.taska.api.auth.admin.inviteuser.v1.ReactorAdminInviteUserServiceGrpc.newReactorStub(authManagedChannel());
    }

    @Bean
    public ru.taska.api.auth.v1.ReactorPersonalAccessTokenServiceGrpc.ReactorPersonalAccessTokenServiceStub personalAccessTokenStub() {
        return ru.taska.api.auth.v1.ReactorPersonalAccessTokenServiceGrpc.newReactorStub(authManagedChannel());
    }

}
