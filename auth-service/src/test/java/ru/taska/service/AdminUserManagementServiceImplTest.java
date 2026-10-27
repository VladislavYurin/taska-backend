package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.impl.AdminUserManagementServiceImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserManagementServiceImpl Unit Tests")
class AdminUserManagementServiceImplTest {

    private static final String REASON = "some reason";
    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "node-1";
    private static final Instant FIXED_UPDATED_AT = Instant.parse("2026-08-22T07:00:00Z");

    @Mock
    private ru.taska.security.pat.PersonalAccessTokenService personalTokens;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private CredentialRepository credentialRepository;

    private AdminUserManagementServiceImpl service;

    private UUID targetUserId;
    private UUID actorUserId;
    private UserStatusRequestDto requestDto;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(personalTokens.revokeAll(org.mockito.Mockito.any(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
                .thenReturn(Mono.empty());
        service = new AdminUserManagementServiceImpl(
                userRepository,
                outboxEventMapper,
                outboxEventService,
                credentialRepository,
                personalTokens
        );
        targetUserId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();

        requestDto = UserStatusRequestDto.builder()
                .targetUserId(targetUserId)
                .actorUserId(actorUserId)
                .reason(REASON)
                .build();

        service = new AdminUserManagementServiceImpl(
                userRepository,
                outboxEventMapper,
                outboxEventService,
                credentialRepository,
                personalTokens
        );
    }

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("Должен заблокировать ACTIVE пользователя, сохранить outbox и вернуть DTO")
        void shouldBlockActiveUser() {
            User user = user(UserStatus.ACTIVE, GlobalRole.USER);

            JsonNode mockPayload = new ObjectMapper().createObjectNode();
            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            Mockito.when(outboxEventMapper.buildUserBlockedPayload(
                            ArgumentMatchers.any(User.class),
                            ArgumentMatchers.eq(UserStatus.ACTIVE),
                            ArgumentMatchers.eq(requestDto)
                    ))
                    .thenReturn(mockPayload);
            Mockito.when(outboxEventService.saveOutboxEvent(
                            ArgumentMatchers.eq(REQUEST_ID),
                            ArgumentMatchers.eq(NODE_ID),
                            ArgumentMatchers.any(),
                            ArgumentMatchers.eq(targetUserId),
                            ArgumentMatchers.any(),
                            ArgumentMatchers.eq(mockPayload)
                    ))
                    .thenReturn(Mono.just(OutboxEvent.builder().build()));

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .assertNext(dto -> {
                        Assertions.assertThat(dto.userId()).isEqualTo(targetUserId);
                        Assertions.assertThat(dto.oldStatus()).isEqualTo(UserStatus.ACTIVE);
                        Assertions.assertThat(dto.newStatus()).isEqualTo(UserStatus.BLOCKED);
                        Assertions.assertThat(dto.changedAt()).isEqualTo(FIXED_UPDATED_AT);
                    })
                    .verifyComplete();

            ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
            Mockito.verify(userRepository).save(savedUserCaptor.capture());
            Assertions.assertThat(savedUserCaptor.getValue().getStatus()).isEqualTo(UserStatus.BLOCKED);
            Assertions.assertThat(savedUserCaptor.getValue().getBlockedFromStatus()).isEqualTo(UserStatus.ACTIVE);

            Mockito.verify(outboxEventService).saveOutboxEvent(
                    ArgumentMatchers.eq(REQUEST_ID),
                    ArgumentMatchers.eq(NODE_ID),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq(targetUserId),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq(mockPayload)
            );
        }

        @Test
        @DisplayName("Должен вернуть NOT_FOUND, если пользователь не найден")
        void shouldReturnNotFoundWhenUserMissing() {
            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.empty());

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.NOT_FOUND)
                    .verify();

            verifyNoPersistence();
        }

        @Test
        @DisplayName("Должен вернуть ABORTED, если пользователь уже BLOCKED")
        void shouldReturnAbortedWhenUserAlreadyBlocked() {
            User user = user(UserStatus.BLOCKED, GlobalRole.USER);
            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.ABORTED)
                    .verify();

            verifyNoPersistence();
        }

        @Test
        @DisplayName("Должен вернуть FAILED_PRECONDITION для последнего active GLOBAL_ADMIN")
        void shouldRejectBlockingLastActiveGlobalAdmin() {
            User admin = user(UserStatus.ACTIVE, GlobalRole.GLOBAL_ADMIN);
            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(admin));
            Mockito.when(userRepository.countByGlobalRoleAndStatus(GlobalRole.GLOBAL_ADMIN, UserStatus.ACTIVE))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.FAILED_PRECONDITION
                            && domainException.getMessage().contains("last active global admin"))
                    .verify();

            verifyNoPersistence();
        }
    }

    @Nested
    @DisplayName("unblockUser")
    class UnblockUserTests {

        @Test
        @DisplayName("Должен разблокировать BLOCKED пользователя, сохранить outbox и вернуть DTO")
        void shouldUnblockBlockedUser() {
            User user = user(UserStatus.BLOCKED, GlobalRole.USER);
            user.setBlockedFromStatus(UserStatus.ACTIVE);
            JsonNode mockPayload = new ObjectMapper().createObjectNode();

            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            Mockito.when(outboxEventMapper.buildUserUnblockedPayload(
                            ArgumentMatchers.any(User.class),
                            ArgumentMatchers.eq(UserStatus.BLOCKED),
                            ArgumentMatchers.eq(requestDto)
                    ))
                    .thenReturn(mockPayload);
            Mockito.when(outboxEventService.saveOutboxEvent(
                            ArgumentMatchers.eq(REQUEST_ID),
                            ArgumentMatchers.eq(NODE_ID),
                            ArgumentMatchers.any(),
                            ArgumentMatchers.eq(targetUserId),
                            ArgumentMatchers.any(),
                            ArgumentMatchers.eq(mockPayload)
                    ))
                    .thenReturn(Mono.just(OutboxEvent.builder().build()));

            StepVerifier.create(service.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                    .assertNext(dto -> {
                        Assertions.assertThat(dto.userId()).isEqualTo(targetUserId);
                        Assertions.assertThat(dto.oldStatus()).isEqualTo(UserStatus.BLOCKED);
                        Assertions.assertThat(dto.newStatus()).isEqualTo(UserStatus.ACTIVE);
                        Assertions.assertThat(dto.changedAt()).isEqualTo(FIXED_UPDATED_AT);
                    })
                    .verifyComplete();

            ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
            Mockito.verify(userRepository).save(savedUserCaptor.capture());
            Assertions.assertThat(savedUserCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
            Assertions.assertThat(savedUserCaptor.getValue().getBlockedFromStatus()).isNull();

            Mockito.verify(outboxEventService).saveOutboxEvent(
                    ArgumentMatchers.eq(REQUEST_ID),
                    ArgumentMatchers.eq(NODE_ID),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq(targetUserId),
                    ArgumentMatchers.any(),
                    ArgumentMatchers.eq(mockPayload)
            );
        }

        @Test
        @DisplayName("Должен вернуть ABORTED, если пользователь не BLOCKED")
        void shouldReturnAbortedWhenUserIsNotBlocked() {
            User user = user(UserStatus.ACTIVE, GlobalRole.USER);
            Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));

            StepVerifier.create(service.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.ABORTED)
                    .verify();

            verifyNoPersistence();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = UserStatus.class, names = {"INVITED", "ACTIVE"})
    void blockUnblockRestoresDurableLifecycleStateWithoutInspectingPassword(UserStatus status) {
        User user = user(status, GlobalRole.USER);
        user.setCreatedVia(ru.taska.entity.UserCreatedVia.OAUTH);
        Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));
        Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));
        var payload = new ObjectMapper().createObjectNode();
        Mockito.when(outboxEventMapper.buildUserBlockedPayload(ArgumentMatchers.any(), ArgumentMatchers.eq(status), ArgumentMatchers.eq(requestDto)))
                .thenReturn(payload);
        Mockito.when(outboxEventMapper.buildUserUnblockedPayload(ArgumentMatchers.any(), ArgumentMatchers.eq(UserStatus.BLOCKED), ArgumentMatchers.eq(requestDto)))
                .thenReturn(payload);
        Mockito.when(outboxEventService.saveOutboxEvent(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(payload)))
                .thenReturn(Mono.just(OutboxEvent.builder().build()));
        StepVerifier.create(service.blockUser(REQUEST_ID, NODE_ID, requestDto))
                .assertNext(result -> Assertions.assertThat(result.newStatus()).isEqualTo(UserStatus.BLOCKED)).verifyComplete();
        Assertions.assertThat(user.getBlockedFromStatus()).isEqualTo(status);
        StepVerifier.create(service.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                .assertNext(result -> Assertions.assertThat(result.newStatus()).isEqualTo(status)).verifyComplete();
        Assertions.assertThat(user.getBlockedFromStatus()).isNull();
        Mockito.verifyNoInteractions(credentialRepository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void legacyBlocksUseInteractiveCredentialsRatherThanPasswordAlone(boolean activated) {
        User user = user(UserStatus.BLOCKED, GlobalRole.USER);
        Mockito.when(userRepository.findByIdForUpdate(targetUserId)).thenReturn(Mono.just(user));
        Mockito.when(credentialRepository.hasInteractiveLogin(targetUserId)).thenReturn(Mono.just(activated));
        Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));
        var payload = new ObjectMapper().createObjectNode();
        Mockito.when(outboxEventMapper.buildUserUnblockedPayload(ArgumentMatchers.any(), ArgumentMatchers.eq(UserStatus.BLOCKED), ArgumentMatchers.eq(requestDto)))
                .thenReturn(payload);
        Mockito.when(outboxEventService.saveOutboxEvent(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(payload)))
                .thenReturn(Mono.just(OutboxEvent.builder().build()));
        StepVerifier.create(service.unblockUser(REQUEST_ID, NODE_ID, requestDto))
                .assertNext(result -> Assertions.assertThat(result.newStatus()).isEqualTo(activated ? UserStatus.ACTIVE : UserStatus.INVITED))
                .verifyComplete();
    }

    private void verifyNoPersistence() {
        Mockito.verify(userRepository, Mockito.never()).save(ArgumentMatchers.any());
        Mockito.verify(outboxEventService, Mockito.never()).saveOutboxEvent(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(UUID.class),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(JsonNode.class)
        );
    }

    private User user(UserStatus status, GlobalRole globalRole) {
        return User.builder()
                .id(targetUserId)
                .email("test@example.com")
                .status(status)
                .globalRole(globalRole)
                .updatedAt(FIXED_UPDATED_AT)
                .build();
    }
}
