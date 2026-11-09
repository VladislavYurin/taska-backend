package ru.taska.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.api.auth.profile.v1.ReactorProfileServiceGrpc;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
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


    private final GrpcClientProperties properties;

    @Bean
    public ManagedChannel projectManagedChannel() {
        return trustedChannel(properties.projectService().host(),properties.projectService().port(),"project-service");
    }

    @Bean
    public ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub() {
        return ReactorProjectServiceGrpc.newReactorStub(projectManagedChannel());
    }

    @Bean
    public ManagedChannel workflowManagedChannel() {
        return trustedChannel(properties.workflowService().host(),properties.workflowService().port(),"workflow-service");
    }

    @Bean
    public ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub() {
        return ReactorWorkflowServiceGrpc.newReactorStub(workflowManagedChannel());
    }

    @Bean
    public ManagedChannel authManagedChannel() {
        return trustedChannel(properties.authService().host(),properties.authService().port(),"auth-service");
    }

    @Bean
    public ReactorProfileServiceGrpc.ReactorProfileServiceStub profileServiceStub() {
        return ReactorProfileServiceGrpc.newReactorStub(authManagedChannel());
    }
}
