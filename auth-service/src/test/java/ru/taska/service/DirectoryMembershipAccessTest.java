package ru.taska.service;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.Test;
import ru.taska.api.project.v1.*;
import ru.taska.config.props.SignupProperties;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.exception.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DirectoryMembershipAccessTest {
    @Test void enabledDirectoryRequiresAnyMembershipAndFailsClosedOnOutage() throws Exception {
        var calls=new AtomicInteger();
        UUID member=UUID.randomUUID();
        Server server=ServerBuilder.forPort(0).addService(new ProjectServiceGrpc.ProjectServiceImplBase() {
            @Override public void hasProjectMembershipInternal(HasProjectMembershipInternalRequest request,
                    io.grpc.stub.StreamObserver<HasProjectMembershipInternalResponse> response) {
                calls.incrementAndGet();
                response.onNext(HasProjectMembershipInternalResponse.newBuilder().setMember(request.getUserId().equals(member.toString())).build());
                response.onCompleted();
            }
        }).build().start();
        var props=new SignupProperties();props.setEnabled(true);
        var access=new DirectoryMembershipAccess(props,"127.0.0.1",server.getPort());
        var user=User.builder().id(member).build();
        try {
            access.check(user).block();
            assertThatThrownBy(() -> access.check(user.toBuilder().id(UUID.randomUUID()).build()).block())
                    .isInstanceOfSatisfying(DomainException.class,e -> { assertThat(e.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED); assertThat(e.getDetails()).containsEntry("reason", "PROJECT_MEMBERSHIP_REQUIRED"); });
            access.check(user.toBuilder().globalRole(GlobalRole.GLOBAL_ADMIN).build()).block();
            props.setEnabled(false);access.check(user).block();
            assertThat(calls).hasValue(2);
            props.setEnabled(true);server.shutdownNow().awaitTermination();
            assertThatThrownBy(() -> access.check(user).block())
                    .isInstanceOfSatisfying(DomainException.class,e -> assertThat(e.getStatus()).isEqualTo(DomainStatus.UNAVAILABLE));
        } finally { access.close(); server.shutdownNow(); }
    }
}
