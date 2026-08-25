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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.impl.AdminUserManagementServiceImpl;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserManagementServiceImpl Unit Tests")
class AdminUserManagementServiceImplTest {

    private static final String REASON = "policy violation";
    private static final Instant FIXED_UPDATED_AT = Instant.parse("2026-08-22T07:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionalOperator transactionalOperator;

    private AdminUserManagementServiceImpl service;

    private UUID targetUserId;
    private UUID actorId;
    private UUID requestId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdminUserManagementServiceImpl(
                userRepository,
                outboxEventMapper,
                outboxEventRepository,
                transactionalOperator
        );
        targetUserId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        requestId = UUID.randomUUID();

        Mockito.lenient().when(transactionalOperator.transactional(ArgumentMatchers.any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Nested
    @DisplayName("blockUser")
    class BlockUserTests {

        @Test
        @DisplayName("Должен заблокировать ACTIVE пользователя, сохранить outbox и вернуть DTO")
        void shouldBlockActiveUser() {
            User user = user(UserStatus.ACTIVE, GlobalRole.USER);
            OutboxEvent outboxEvent = buildOutboxEvent("USER_BLOCKED");

            stubBlockOutboxMapping(UserStatus.ACTIVE, outboxEvent);
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.just(user));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(service.blockUser(targetUserId, REASON, actorId, requestId))
                    .assertNext(dto -> {
                        Assertions.assertThat(dto.userId()).isEqualTo(targetUserId);
                        Assertions.assertThat(dto.oldStatus()).isEqualTo(UserStatus.ACTIVE);
                        Assertions.assertThat(dto.newStatus()).isEqualTo(UserStatus.BLOCKED);
                        Assertions.assertThat(dto.updatedAt()).isEqualTo(FIXED_UPDATED_AT);
                    })
                    .verifyComplete();

            ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
            Mockito.verify(userRepository).save(savedUserCaptor.capture());
            Assertions.assertThat(savedUserCaptor.getValue().getStatus()).isEqualTo(UserStatus.BLOCKED);

            ArgumentCaptor<OutboxEvent> savedOutboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            Mockito.verify(outboxEventRepository).save(savedOutboxCaptor.capture());
            Assertions.assertThat(savedOutboxCaptor.getValue()).isSameAs(outboxEvent);

            ArgumentCaptor<User> mappedUserCaptor = ArgumentCaptor.forClass(User.class);
            Mockito.verify(outboxEventMapper).buildUserBlockedOutboxEvent(
                    mappedUserCaptor.capture(),
                    ArgumentMatchers.eq(UserStatus.ACTIVE),
                    ArgumentMatchers.eq(REASON),
                    ArgumentMatchers.eq(actorId),
                    ArgumentMatchers.eq(requestId)
            );
            Assertions.assertThat(mappedUserCaptor.getValue().getStatus()).isEqualTo(UserStatus.BLOCKED);
        }

        @Test
        @DisplayName("Должен вернуть NOT_FOUND, если пользователь не найден")
        void shouldReturnNotFoundWhenUserMissing() {
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.empty());

            StepVerifier.create(service.blockUser(targetUserId, REASON, actorId, requestId))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.NOT_FOUND)
                    .verify();

            verifyNoPersistence();
        }

        @Test
        @DisplayName("Должен вернуть ABORTED, если пользователь уже BLOCKED")
        void shouldReturnAbortedWhenUserAlreadyBlocked() {
            User user = user(UserStatus.BLOCKED, GlobalRole.USER);
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.just(user));

            StepVerifier.create(service.blockUser(targetUserId, REASON, actorId, requestId))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.ABORTED)
                    .verify();

            verifyNoPersistence();
        }

        @Test
        @DisplayName("Должен вернуть FAILED_PRECONDITION для последнего active GLOBAL_ADMIN")
        void shouldRejectBlockingLastActiveGlobalAdmin() {
            User admin = user(UserStatus.ACTIVE, GlobalRole.GLOBAL_ADMIN);
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.just(admin));
            Mockito.when(userRepository.countByGlobalRoleAndStatus(GlobalRole.GLOBAL_ADMIN, UserStatus.ACTIVE))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(service.blockUser(targetUserId, REASON, actorId, requestId))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.FAILED_PRECONDITION
                            && domainException.getMessage().contains("last active global admin"))
                    .verify();

            verifyNoPersistence();
        }

        @Test
        @DisplayName("Должен вернуть FAILED_PRECONDITION при пустом reason")
        void shouldRejectBlankReason() {
            StepVerifier.create(service.blockUser(targetUserId, "  ", actorId, requestId))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.FAILED_PRECONDITION)
                    .verify();

            Mockito.verifyNoInteractions(userRepository);
            Mockito.verifyNoInteractions(outboxEventMapper);
            Mockito.verifyNoInteractions(outboxEventRepository);
        }
    }

    @Nested
    @DisplayName("unblockUser")
    class UnblockUserTests {

        @Test
        @DisplayName("Должен разблокировать BLOCKED пользователя, сохранить outbox и вернуть DTO")
        void shouldUnblockBlockedUser() {
            User user = user(UserStatus.BLOCKED, GlobalRole.USER);
            OutboxEvent outboxEvent = buildOutboxEvent("USER_UNBLOCKED");

            stubUnblockOutboxMapping(UserStatus.BLOCKED, outboxEvent);
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.just(user));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(service.unblockUser(targetUserId, REASON, actorId, requestId))
                    .assertNext(dto -> {
                        Assertions.assertThat(dto.userId()).isEqualTo(targetUserId);
                        Assertions.assertThat(dto.oldStatus()).isEqualTo(UserStatus.BLOCKED);
                        Assertions.assertThat(dto.newStatus()).isEqualTo(UserStatus.ACTIVE);
                        Assertions.assertThat(dto.updatedAt()).isEqualTo(FIXED_UPDATED_AT);
                    })
                    .verifyComplete();

            ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
            Mockito.verify(userRepository).save(savedUserCaptor.capture());
            Assertions.assertThat(savedUserCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);

            ArgumentCaptor<OutboxEvent> savedOutboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
            Mockito.verify(outboxEventRepository).save(savedOutboxCaptor.capture());
            Assertions.assertThat(savedOutboxCaptor.getValue()).isSameAs(outboxEvent);
        }

        @Test
        @DisplayName("Должен вернуть ABORTED, если пользователь не BLOCKED")
        void shouldReturnAbortedWhenUserIsNotBlocked() {
            User user = user(UserStatus.ACTIVE, GlobalRole.USER);
            Mockito.when(userRepository.findById(targetUserId)).thenReturn(Mono.just(user));

            StepVerifier.create(service.unblockUser(targetUserId, REASON, actorId, requestId))
                    .expectErrorMatches(error -> error instanceof DomainException domainException
                            && domainException.getStatus() == DomainStatus.ABORTED)
                    .verify();

            verifyNoPersistence();
        }
    }

    private void stubBlockOutboxMapping(UserStatus oldStatus, OutboxEvent outboxEvent) {
        Mockito.when(outboxEventMapper.buildUserBlockedOutboxEvent(
                ArgumentMatchers.any(User.class),
                ArgumentMatchers.eq(oldStatus),
                ArgumentMatchers.eq(REASON),
                ArgumentMatchers.eq(actorId),
                ArgumentMatchers.eq(requestId)
        )).thenReturn(outboxEvent);
    }

    private void stubUnblockOutboxMapping(UserStatus oldStatus, OutboxEvent outboxEvent) {
        Mockito.when(outboxEventMapper.buildUserUnblockedOutboxEvent(
                ArgumentMatchers.any(User.class),
                ArgumentMatchers.eq(oldStatus),
                ArgumentMatchers.eq(REASON),
                ArgumentMatchers.eq(actorId),
                ArgumentMatchers.eq(requestId)
        )).thenReturn(outboxEvent);
    }

    private void verifyNoPersistence() {
        Mockito.verify(userRepository, Mockito.never()).save(ArgumentMatchers.any());
        Mockito.verify(outboxEventMapper, Mockito.never()).buildUserBlockedOutboxEvent(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()
        );
        Mockito.verify(outboxEventMapper, Mockito.never()).buildUserUnblockedOutboxEvent(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()
        );
        Mockito.verify(outboxEventRepository, Mockito.never()).save(ArgumentMatchers.any());
    }

    private OutboxEvent buildOutboxEvent(String eventType) {
        return OutboxEvent.builder()
                .aggregateType("USER")
                .aggregateId(targetUserId)
                .eventType(eventType)
                .requestId(requestId.toString())
                .build();
    }

    private User user(UserStatus status, GlobalRole globalRole) {
        return User.builder()
                .id(targetUserId)
                .login("test-user")
                .email("test@example.com")
                .status(status)
                .globalRole(globalRole)
                .updatedAt(FIXED_UPDATED_AT)
                .build();
    }
}
