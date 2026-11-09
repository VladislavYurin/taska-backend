package ru.taska.service;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.config.props.SignupProperties;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import java.util.concurrent.TimeUnit;

@Component
public class DirectoryMembershipAccess {
    private final SignupProperties signup;
    private final ManagedChannel channel;
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub client;
    public DirectoryMembershipAccess(SignupProperties signup,String host,int port){this(signup,host,port,false);}
    @org.springframework.beans.factory.annotation.Autowired
    public DirectoryMembershipAccess(SignupProperties signup,
            @Value("${grpc.client.project-service.host:localhost}") String host,
            @Value("${grpc.client.project-service.port:9096}") int port,@Value("${taska.rpc.context.enabled:false}") boolean trusted) {
        this.signup = signup;
        this.channel = trusted?null:ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.client = channel==null?null:ReactorProjectServiceGrpc.newReactorStub(channel);
    }
    public Mono<Void> check(User user) {
        if(client==null)return Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Tenant directory requires its scoped adapter"));
        if (!signup.isEnabled() || user.getGlobalRole() == GlobalRole.GLOBAL_ADMIN) return Mono.empty();
        return client.withDeadlineAfter(3, TimeUnit.SECONDS).hasProjectMembershipInternal(
                        HasProjectMembershipInternalRequest.newBuilder().setUserId(user.getId().toString()).build())
                .onErrorMap(io.grpc.StatusRuntimeException.class, error -> new DomainException(DomainStatus.UNAVAILABLE, "Project membership service is unavailable"))
                .filter(HasProjectMembershipInternalResponse::getMember)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Project membership is required", java.util.Map.of("reason", "PROJECT_MEMBERSHIP_REQUIRED"))))
                .then();
    }
    @PreDestroy public void close() { if(channel!=null)channel.shutdown(); }
}
