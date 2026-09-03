package ru.taska.transport.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.AbstractIT;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequestBody;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.ReactorAdminInviteUserServiceGrpc;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserResponse;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.api.common.v1.Header;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import testUtil.TestConstantHolder;
import testUtil.TestGrpcDataGenerator;

class AdminInviteUserGrpcServiceIT extends AbstractIT {

    private static ReactorAdminInviteUserServiceGrpc.ReactorAdminInviteUserServiceStub stub;

    private static ManagedChannel channel;

    private static final Header header = TestGrpcDataGenerator.getRequestHeader();

    private static final InviteUserRequestBody body = TestGrpcDataGenerator.getInviteUserRequestBody();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeAll
    static void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                                                      .usePlaintext()
                                                      .build();
        stub = ReactorAdminInviteUserServiceGrpc.newReactorStub(channel);
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll().block();
        inviteTokenRepository.deleteAll().block();
        outboxEventRepository.deleteAll().block();
    }

    @AfterAll
    static void closeResources() {
        if (channel != null && !channel.isShutdown())
            channel.shutdown();
    }

    @Test
    void inviteUser_Success_ShouldSaveDataAndReturnResponse() {
        InviteUserRequest request = buildRequest();
        Mono<InviteUserResponse> result = stub.inviteUser(Mono.just(request));
        AtomicReference<UUID> userIdRef = new AtomicReference<>();
        StepVerifier.create(result)
                    .assertNext(response -> {
                        Assertions.assertThat(response.getUserId()).isNotNull();
                        Assertions.assertThat(response.getStatus()).isEqualTo(UserStatus.USER_STATUS_INVITED);
                        userIdRef.set(UUID.fromString(response.getUserId()));
                    })
                    .verifyComplete();
        UUID userId = userIdRef.get();

        StepVerifier.create(userRepository.findByEmail(TestConstantHolder.TEST_USER_EMAIL))
                    .assertNext(user -> {
                        Assertions.assertThat(user.getId()).isEqualTo(userId);
                        Assertions.assertThat(user.getLogin()).isNotNull();
                        Assertions.assertThat(user.getDisplayName()).isEqualTo(TestConstantHolder.TEST_USER_DISPLAY_NAME);
                        Assertions.assertThat(user.getStatus()).isEqualTo(ru.taska.entity.UserStatus.INVITED);
                    })
                    .verifyComplete();

        StepVerifier.create(inviteTokenRepository.findByUserId(userId))
                    .assertNext(inviteToken -> {
                        Assertions.assertThat(inviteToken.getTokenHash()).isNotBlank();
                        Assertions.assertThat(inviteToken.getExpiresAt()).isNotNull();
                    })
                    .verifyComplete();

        StepVerifier.create(outboxEventRepository.findByAggregateId(userId))
                .assertNext(event -> {
                    Assertions.assertThat(event.getAggregateType()).isEqualTo("user");
                    Assertions.assertThat(event.getEventType()).isEqualTo("UserInvited");
                    Assertions.assertThat(event.getPayload().get("rawInviteToken").asString()).isNotBlank();
                    Assertions.assertThat(event.getPayload().get("email").asString()).isEqualTo(TestConstantHolder.TEST_USER_EMAIL);
                    Assertions.assertThat(event.getPayload().get("expiresAt")).isNotNull();
                })
                    .verifyComplete();
    }

    @Test
    void inviteUser_DuplicateEmail_ShouldReturnAlreadyExists() {
        InviteUserRequest firstRequest = buildRequest();

        stub.inviteUser(Mono.just(firstRequest)).block();

        InviteUserRequest secondRequest = buildRequest();

        StepVerifier.create(stub.inviteUser(Mono.just(secondRequest)))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(StatusRuntimeException.class);
                        StatusRuntimeException e = (StatusRuntimeException) throwable;
                        Assertions.assertThat(e.getStatus().getCode()).isEqualTo(Code.ALREADY_EXISTS);
                        Assertions.assertThat(e.getStatus().getDescription()).contains("User with email test@test.com already exists");
                    })
                    .verify();
    }

    @ParameterizedTest
    @MethodSource("invalidRequestsProvider")
    void inviteUser_invalidRequest_shouldReturnInvalidArgument(
            InviteUserRequest request,
            String expectedMessagePart) {

        StepVerifier.create(stub.inviteUser(Mono.just(request)))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable)
                                .isInstanceOf(StatusRuntimeException.class);

                        StatusRuntimeException statusEx = (StatusRuntimeException) throwable;

                        Assertions.assertThat(statusEx.getStatus().getCode())
                                .isEqualTo(Code.INVALID_ARGUMENT);

                        Assertions.assertThat(statusEx.getStatus().getDescription())
                                .contains(expectedMessagePart);
                    })
                    .verify();
    }

    private static Stream<Arguments> invalidRequestsProvider() {
        return Stream.of(
                // 1. requestId - пустая строка ""
                Arguments.of(InviteUserRequest.newBuilder()
                                              .setHeader(Header.newBuilder()
                                                               .setRequestId(TestConstantHolder.EMPTY_STRING)
                                                               .setNodeId(TestConstantHolder.HEADER_NODE_ID)
                                                               .build())
                                              .setBody(body)
                                              .build(),
                        "header.requestId must not be blank"
                ),
                // 2. requestId == null (не установлен)
                Arguments.of(InviteUserRequest.newBuilder()
                                              .setHeader(Header.newBuilder()
                                                               .setNodeId(TestConstantHolder.HEADER_NODE_ID)
                                                               .build())
                                              .setBody(body)
                                              .build(),
                        "header.requestId must not be blank"
                ),
                // 3. nodeId - пустая строка ""
                Arguments.of(InviteUserRequest.newBuilder()
                                              .setHeader(Header.newBuilder()
                                                               .setRequestId(TestConstantHolder.HEADER_REQUEST_ID)
                                                               .setNodeId(TestConstantHolder.EMPTY_STRING)
                                                               .build())
                                              .setBody(body)
                                              .build(),
                        "header.nodeId must not be blank"
                ),
                // 4. nodeId ==  null
                Arguments.of(InviteUserRequest.newBuilder()
                                                   .setHeader(Header.newBuilder()
                                                                    .setRequestId(TestConstantHolder.HEADER_REQUEST_ID)
                                                                    .build())
                                                   .setBody(body)
                                                   .build(),
                             "header.nodeId must not be blank"
                ),
                // 5. email - пустая строка ""
                Arguments.of(InviteUserRequest.newBuilder()
                                              .setHeader(header)
                                              .setBody(InviteUserRequestBody.newBuilder(body)
                                                                          .setEmail(TestConstantHolder.EMPTY_STRING)
                                                                          .build())
                                              .build(),
                        "body.email must not be blank"
                ),
                // 6. email == null
                Arguments.of(InviteUserRequest.newBuilder()
                                                   .setHeader(header)
                                                   .setBody(InviteUserRequestBody.newBuilder()
                                                                               .setDisplayName(TestConstantHolder.TEST_USER_DISPLAY_NAME)
                                                                               .build())
                                                   .build(),
                             "body.email must not be blank"
                ),
                // 7. displayName - пустая строка ""
                Arguments.of(InviteUserRequest.newBuilder()
                                                   .setHeader(header)
                                                   .setBody(InviteUserRequestBody.newBuilder(body)
                                                                               .setDisplayName(TestConstantHolder.EMPTY_STRING)
                                                                               .build())
                                                   .build(),
                             "body.displayName must not be blank"
                ),
                // 8. displayName == null
                Arguments.of(InviteUserRequest.newBuilder()
                                              .setHeader(header)
                                              .setBody(InviteUserRequestBody.newBuilder()
                                                                          .setEmail(TestConstantHolder.TEST_USER_EMAIL)
                                                                          .build())
                                              .build(),
                        "body.displayName must not be blank"
                )
        );
    }

    private static InviteUserRequest buildRequest() {
        InviteUserRequestBody body = InviteUserRequestBody.newBuilder()
                                                      .setEmail(TestConstantHolder.TEST_USER_EMAIL)
                                                      .setDisplayName(TestConstantHolder.TEST_USER_DISPLAY_NAME)
                                                      .build();
        return InviteUserRequest.newBuilder().setHeader(header).setBody(body).build();
    }
}