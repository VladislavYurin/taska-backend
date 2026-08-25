package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.UserStatusChangeDto;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.repository.CommonOutboxEventRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.AdminUserManagementService;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserManagementServiceImpl implements AdminUserManagementService {

    private final UserRepository userRepository;
    private final OutboxEventMapper outboxEventMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionalOperator transactionalOperator;

    @Override
    public Mono<UserStatusChangeDto> blockUser(UUID targetUserId, String reason, UUID actorId, UUID requestId) {
        if (targetUserId == null || reason == null || reason.isBlank() || actorId == null || requestId == null) {
            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Required parameters are missing"));
        }

        return userRepository.findById(targetUserId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(this::validateBlockStatusTransition)
                .flatMap(this::verifyLastGlobalAdmin)
                .flatMap(user -> {
                    UserStatus oldStatus = user.getStatus();
                    user.setStatus(UserStatus.BLOCKED);

                    return userRepository.save(user)
                            .flatMap(savedUser -> {
                                OutboxEvent outboxEvent = outboxEventMapper.buildUserBlockedOutboxEvent(
                                        savedUser, oldStatus, reason, actorId, requestId
                                );
                                return outboxEventRepository.save(outboxEvent).thenReturn(savedUser);
                            })
                            .map(savedUser -> new UserStatusChangeDto(
                                    savedUser.getId(),
                                    oldStatus,
                                    savedUser.getStatus(),
                                    savedUser.getUpdatedAt()
                            ));
                })
                .as(transactionalOperator::transactional);
    }

    @Override
    public Mono<UserStatusChangeDto> unblockUser(UUID targetUserId, String reason, UUID actorId, UUID requestId) {
        if (targetUserId == null || reason == null || reason.isBlank() || actorId == null || requestId == null) {
            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "Required parameters are missing"));
        }

        return userRepository.findById(targetUserId)
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(this::validateUnblockStatusTransition)
                .flatMap(user -> {
                    UserStatus oldStatus = user.getStatus();
                    user.setStatus(UserStatus.ACTIVE);

                    return userRepository.save(user)
                            .flatMap(savedUser -> {
                                OutboxEvent outboxEvent = outboxEventMapper.buildUserUnblockedOutboxEvent(
                                        savedUser, oldStatus, reason, actorId, requestId
                                );
                                return outboxEventRepository.save(outboxEvent).thenReturn(savedUser);
                            })
                            .map(savedUser -> new UserStatusChangeDto(
                                    savedUser.getId(),
                                    oldStatus,
                                    savedUser.getStatus(),
                                    savedUser.getUpdatedAt()
                            ));
                })
                .as(transactionalOperator::transactional);
    }

    private Mono<User> verifyLastGlobalAdmin(User user) {
        if (user.getGlobalRole() == GlobalRole.GLOBAL_ADMIN && user.getStatus() == UserStatus.ACTIVE) {
            return userRepository.countByGlobalRoleAndStatus(GlobalRole.GLOBAL_ADMIN, UserStatus.ACTIVE)
                    .flatMap(count -> {
                        if (count <= 1) {
                            return Mono.error(new DomainException(
                                    DomainStatus.FAILED_PRECONDITION,
                                    "Cannot block the last active global admin"
                            ));
                        }
                        return Mono.just(user);
                    });
        }
        return Mono.just(user);
    }

    private Mono<User> validateBlockStatusTransition(User user) {
        UserStatus currentStatus = user.getStatus();
        if (currentStatus == UserStatus.ACTIVE || currentStatus == UserStatus.INVITED) {
            return Mono.just(user);
        }
        return Mono.error(new DomainException(
                DomainStatus.ABORTED,
                "Cannot block user with current status: " + currentStatus
        ));
    }

    private Mono<User> validateUnblockStatusTransition(User user) {
        UserStatus currentStatus = user.getStatus();
        if (currentStatus == UserStatus.BLOCKED) {
            return Mono.just(user);
        }
        return Mono.error(new DomainException(
                DomainStatus.ABORTED,
                "Cannot unblock user with current status: " + currentStatus
        ));
    }



}
