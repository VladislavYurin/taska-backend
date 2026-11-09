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

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel workflowIssueManagedChannel(@Value("${ISSUE_GRPC_HOST:localhost}") String host,
                                                     @Value("${ISSUE_GRPC_PORT:9094}") int port) {
        return trustedChannel(host,port,"issue-service");
    }

    @Bean
    public ReactorIssueServiceGrpc.ReactorIssueServiceStub workflowIssueServiceStub(
            @org.springframework.beans.factory.annotation.Qualifier("workflowIssueManagedChannel") ManagedChannel channel) {
        return ReactorIssueServiceGrpc.newReactorStub(channel);
    }

    @Bean
    public ManagedChannel projectManagedChannel() {
        return trustedChannel(properties.projectService().host(),properties.projectService().port(),"project-service");
    }

    @Bean
    public ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub() {
        return ReactorProjectServiceGrpc.newReactorStub(projectManagedChannel());
    }
}
