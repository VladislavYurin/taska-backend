package ru.taska.service;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.*;
import ru.taska.mapper.AuthMapper;
import ru.taska.mapper.UserProfileMapper;
import ru.taska.service.impl.UserServiceImpl;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

class UserServiceImplTest {
    private final ReactorProfileServiceGrpc.ReactorProfileServiceStub stub = Mockito.mock(ReactorProfileServiceGrpc.ReactorProfileServiceStub.class);
    private final GatewayContext context = new GatewayContext("request", "gateway", GatewayUserContext.builder()
            .userId(UUID.randomUUID().toString()).email("user@example.com").login("user")
            .displayName("User").status(GatewayUserStatus.ACTIVE).globalRole(GlobalRole.USER).build());
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        var properties = Mockito.mock(GrpcClientProperties.class);
        var authProperties = Mockito.mock(GrpcClientProperties.Service.class);
        Mockito.when(properties.authService()).thenReturn(authProperties);
        Mockito.when(authProperties.deadlineDuration()).thenReturn(Duration.ofSeconds(2));
        Mockito.when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        service = new UserServiceImpl(new AuthMapper(), new GrpcUserProfileServiceClient(stub, new UserProfileMapper(), properties));
    }

    @Test
    void currentUserProfile_includesAvatarAndSendsActorAndTrace() {
        var id = UUID.randomUUID().toString();
        var avatar = AvatarResponse.newBuilder().setId(id).setContentType("image/png").setSizeBytes(100)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1000)).setDownloadUrl("https://storage/avatar").build();
        Mockito.when(stub.getUserProfile(any(GetUserProfileRequest.class)))
                .thenReturn(Mono.just(GetUserProfileResponse.newBuilder().setAvatar(avatar).build()));
        StepVerifier.create(service.getMyInfo(context)).assertNext(result -> {
            assertThat(result.getId()).isEqualTo(context.userContext().userId());
            assertThat(result.getAvatar().getId()).isEqualTo(id);
            assertThat(result.getAvatar().getContentType()).isEqualTo("image/png");
            assertThat(result.getAvatar().getSizeBytes()).isEqualTo(100);
            assertThat(result.getAvatar().getCreatedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(1000));
            assertThat(result.getAvatar().getDownloadUrl()).isEqualTo("https://storage/avatar");
            assertThat(result.getAvatarUnavailable()).isFalse();
        }).verifyComplete();
        Mockito.verify(stub).getUserProfile(argThat((GetUserProfileRequest request) ->
                request.getBody().getUserId().equals(context.userContext().userId())
                        && request.getHeader().getRequestId().equals("request")
                        && request.getHeader().getNodeId().equals("gateway")));
        Mockito.verify(stub).withDeadlineAfter(2000, TimeUnit.MILLISECONDS);
    }

    @Test
    void absentAvatar_keepsCoreUserData() {
        Mockito.when(stub.getUserProfile(any(GetUserProfileRequest.class)))
                .thenReturn(Mono.just(GetUserProfileResponse.getDefaultInstance()));
        assertCoreProfile(false);
    }

    @ParameterizedTest
    @EnumSource(value = Status.Code.class, names = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "NOT_FOUND"})
    void profileFailure_keepsCoreUserData(Status.Code code) {
        Mockito.when(stub.getUserProfile(any(GetUserProfileRequest.class)))
                .thenReturn(Mono.error(Status.fromCode(code).asRuntimeException()));
        assertCoreProfile(true);
    }

    private void assertCoreProfile(boolean avatarUnavailable) {
        StepVerifier.create(service.getMyInfo(context)).assertNext(result -> {
            assertThat(result.getAvatar()).isNull();
            assertThat(result.getAvatarUnavailable()).isEqualTo(avatarUnavailable);
            assertThat(result.getId()).isEqualTo(context.userContext().userId());
            assertThat(result.getLogin()).isEqualTo("user");
            assertThat(result.getEmail()).isEqualTo("user@example.com");
            assertThat(result.getDisplayName()).isEqualTo("User");
        }).verifyComplete();
    }
}
