package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.entity.CredentialType;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.OutboxEventMapper;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.AdminUserManagementService;
import ru.taska.service.OutboxEventService;
import tools.jackson.databind.JsonNode;

/**
 * Реализация сервиса административного управления статусами пользователей.
 *
 * <p>Обрабатывает транзакционные операции по изменению статуса пользователя
 * с сохранением Outbox-событий.</p>
 *
 * @see AdminUserManagementService
 */
@Service
@RequiredArgsConstructor
public class AdminUserManagementServiceImpl implements AdminUserManagementService {

    private final UserRepository userRepository;
    private final OutboxEventMapper outboxEventMapper;
    private final OutboxEventService outboxEventService;
    private final CredentialRepository credentialRepository;

    /**
     * Снимает блокировку с пользователя (изменяет статус)
     */
    @Override
    @Transactional
    public Mono<UserStatusResponseDto> blockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return userRepository.findById(requestDto.targetUserId())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(this::validateBlockStatusTransition)
                .flatMap(this::verifyLastGlobalAdmin)
                .flatMap(user -> {
                    UserStatus oldStatus = user.getStatus();
                    user.setStatus(UserStatus.BLOCKED);

                    return userRepository.save(user)
                            .flatMap(savedUser -> {

                                JsonNode payload = outboxEventMapper.buildUserBlockedPayload(
                                        savedUser,oldStatus, requestDto
                                );
                                return outboxEventService
                                        .saveOutboxEvent(requestId,nodeId, AggregateType.USER, savedUser.getId(), EventType.USER_BLOCKED,payload)
                                        .thenReturn(savedUser);

                            })
                            .map(savedUser ->
                                    UserStatusResponseDto.builder()
                                            .userId(savedUser.getId())
                                            .oldStatus(oldStatus)
                                            .newStatus(savedUser.getStatus())
                                            .changedAt(savedUser.getUpdatedAt())
                                            .build()
                            );
                });
    }

    /**
     * Блокирует пользователя (изменяет статус)
     */
    @Override
    @Transactional
    public Mono<UserStatusResponseDto> unblockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return userRepository.findById(requestDto.targetUserId())
                .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")))
                .flatMap(this::validateUnblockStatusTransition)
                .flatMap(user -> {
                    UserStatus oldStatus = user.getStatus();
                    user.setStatus(UserStatus.ACTIVE);

                    return userRepository.save(user)
                            .flatMap(savedUser -> {

                                JsonNode payload = outboxEventMapper.buildUserUnblockedPayload(
                                        savedUser, oldStatus, requestDto
                                );
                                return outboxEventService
                                        .saveOutboxEvent(requestId,nodeId, AggregateType.USER, savedUser.getId(), EventType.USER_UNBLOCKED,payload)
                                        .thenReturn(savedUser);

                            })
                            .map(savedUser ->
                                    UserStatusResponseDto.builder()
                                            .userId(savedUser.getId())
                                            .oldStatus(oldStatus)
                                            .newStatus(savedUser.getStatus())
                                            .changedAt(savedUser.getUpdatedAt())
                                            .build()
                            );
                });
    }

    /**
     * Сбрасывает CredentialLockout пользователя, у которого было много неудачных попыток входа
     */
    @Override
    @Transactional
    public Mono<UserCredentialStateResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return userRepository.findById(requestDto.targetUserId())
                .switchIfEmpty(
                        Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found"))
                )
                .flatMap(user -> {
                    if (user.getStatus() != UserStatus.LOCKED) {
                        return Mono.error(new DomainException(
                                DomainStatus.FAILED_PRECONDITION,
                                "User is not in LOCKED status"
                        ));
                    }
                    return credentialRepository
                            .findByUserIdAndCredentialType(user.getId(), CredentialType.PASSWORD)
                            .switchIfEmpty(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Credential not found")))
                            .flatMap(credential -> {
                                UserStatus oldStatus = user.getStatus();

                                UserCredentialStateResponseDto.CredentialState oldState = UserCredentialStateResponseDto.CredentialState.builder()
                                        .failedAttempts(credential.getFailedAttempts())
                                        .lockedUntil(credential.getLockedUntil())
                                        .lastFailedAt(credential.getLastFailedAt())
                                        .build();

                                credential.setFailedAttempts(0);
                                credential.setLockedUntil(null);
                                credential.setLastFailedAt(null);

                                user.setStatus(UserStatus.ACTIVE);

                                UserCredentialStateResponseDto.CredentialState newState = UserCredentialStateResponseDto.CredentialState.empty();

                                return credentialRepository.save(credential)
                                        .then(userRepository.save(user))
                                        .map(savedUser -> UserCredentialStateResponseDto.builder()
                                                .userId(savedUser.getId())
                                                .oldStatus(oldStatus)
                                                .newStatus(savedUser.getStatus())
                                                .changedAt(savedUser.getUpdatedAt())
                                                .oldCredentialState(oldState)
                                                .newCredentialState(newState)
                                                .build()
                                        );
                            });
                });

    }

    ///=================== Utils ======================

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
