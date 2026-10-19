package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.InviteUserResponse;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AdminInviteUserMapper;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.AdminInviteUserService;
import ru.taska.service.DirectoryAccess;
import ru.taska.service.InviteTokenService;
import ru.taska.util.DirectoryInput;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminInviteUserServiceImpl implements AdminInviteUserService {
    private final UserRepository users;
    private final InviteTokenRepository tokens;
    private final InviteTokenService tokenService;
    private final OutboxEventRepository outbox;
    private final AdminInviteUserMapper mapper;
    private final DirectoryAccess access;
    private final DirectoryInput input;
    private final DatabaseClient database;
    @Value("${invitation.resend-cooldown:1m}")
    private Duration resendCooldown;

    @Override
    @Transactional
    public Mono<InviteUserResponse> inviteUser(InviteUserRequest request) {
        return Mono.defer(() -> access.requireActor(UUID.fromString(request.getBody().getActorUserId()), true)
                .flatMap(actor -> {
                    String email = input.email(request.getBody().getEmail());
                    String name = input.displayName(request.getBody().getDisplayName(), email);
                    // Serializes both concurrent first invitations and resends, including email case variants.
                    return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))")
                            .bind("email", email).map((row, metadata) -> true).one()
                            .then(Mono.defer(() -> users.findByCanonicalEmailForUpdate(email)
                                    .flatMap(user -> inviteExisting(request, user))
                                    .switchIfEmpty(Mono.defer(() -> users.save(User.builder()
                                                    .email(email).login("invited_" + UUID.randomUUID())
                                                    .displayName(name).status(UserStatus.INVITED).globalRole(GlobalRole.USER).build())
                                            .flatMap(user -> queue(request, user, true))))));
                }));
    }

    private Mono<InviteUserResponse> inviteExisting(InviteUserRequest request, User user) {
        if (user.getStatus() != UserStatus.INVITED) {
            return Mono.error(new DomainException(DomainStatus.ALREADY_EXISTS, "An account with this email already exists"));
        }
        return tokens.findLatestByUserId(user.getId())
                .flatMap(token -> {
                    boolean valid = token.getUsedAt() == null && token.getExpiresAt().isAfter(Instant.now());
                    if (!request.getBody().getResend() && valid) {
                        return Mono.just(response(user, false, false, token.getExpiresAt()));
                    }
                    Instant allowedAt = token.getCreatedAt().plus(resendCooldown);
                    if (Instant.now().isBefore(allowedAt)) {
                        long seconds = Math.max(1, Duration.between(Instant.now(), allowedAt).toSeconds() + 1);
                        return Mono.error(new DomainException(DomainStatus.RESOURCE_EXHAUSTED,
                                "Invitation was sent recently; try again later", Map.of("retryAfterSeconds", Long.toString(seconds))));
                    }
                    return queue(request, user, false);
                }).switchIfEmpty(Mono.defer(() -> queue(request, user, false)));
    }

    private Mono<InviteUserResponse> queue(InviteUserRequest request, User user, boolean created) {
        return tokens.revokeUnusedForUser(user.getId())
                .then(tokenService.createInviteToken(user.getId()))
                .flatMap(token -> {
                    var event = mapper.buildUserInvitedOutboxEvent(token, user.getEmail(), request.getHeader().getRequestId());
                    ((tools.jackson.databind.node.ObjectNode) event.getPayload()).put("invitedBy", request.getBody().getActorUserId());
                    return outbox.save(event).thenReturn(response(user, created, true, token.getExpiresAt()));
                });
    }

    private InviteUserResponse response(User user, boolean created, boolean queued, Instant expiresAt) {
        return InviteUserResponse.newBuilder().setUserId(user.getId().toString())
                .setStatus(ru.taska.api.common.v1.UserStatus.USER_STATUS_INVITED)
                .setCreated(created).setDeliveryQueued(queued).setExpiresAt(expiresAt.toString()).build();
    }
}
