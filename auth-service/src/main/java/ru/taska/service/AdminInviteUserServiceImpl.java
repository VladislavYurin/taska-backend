package ru.taska.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.inviteuser.v1.AdminCreateUserRequest;
import ru.taska.api.auth.admin.inviteuser.v1.UserCreatedResponse;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.AdminInviteUserMapper;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminInviteUserServiceImpl implements AdminInviteUserService {

    private final UserRepository userRepository;
    private final InviteTokenService inviteTokenService;
    private final OutboxEventRepository outboxEventRepository;
    private final AdminInviteUserMapper inviteUserMapper;
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile("constraint \"(\\w+)\"");

    /**
     * Создает нового пользователя с параметрами из запроса,
     * Генерирует invite токен,
     * Создает outbox-событие USER_INVITED
     * @param request - AdminCreateUserRequest grpc-запрос
     * @return - UserCreatedResponse grpc-ответ (userId, status=INVITED)
     */
    @Override
    @Transactional
    public Mono<UserCreatedResponse> inviteUser(AdminCreateUserRequest request) {
        return userRepository.save(inviteUserMapper.buildInvitedUserFromRequest(request))
                .onErrorResume(DataIntegrityViolationException.class, ex -> {
                    Optional<String> constraint = extractConstraintName(ex);
                    if (constraint.isPresent()) {
                        if ("users_email_key".equals(constraint.get())) {
                            return Mono.error(new DomainException(
                                    DomainStatus.ALREADY_EXISTS,
                                    "User with email " + request.getBody().getEmail() + " already exists"
                            ));
                        } else {
                            return Mono.error(new DomainException(
                                    DomainStatus.INTERNAL,
                                    "Data integrity violation: " + constraint.get()
                            ));
                        }
                    } else {
                        log.error("Could not extract constraint name from DataIntegrityViolationException", ex);
                        return Mono.error(new DomainException(
                                DomainStatus.INTERNAL,
                                "Unexpected data integrity violation"));
                    }
                })
                .flatMap(user -> inviteTokenService.createInviteToken(user.getId())
                        .flatMap(inviteToken -> outboxEventRepository
                                .save(inviteUserMapper.buildUserInvitedOutboxEvent(
                                        inviteToken, user.getEmail(), request.getHeader().getRequestId())))
                        .thenReturn(user))
                .map(user -> UserCreatedResponse.newBuilder()
                        .setUserId(String.valueOf(user.getId()))
                        .setStatus(UserStatus.USER_STATUS_INVITED)
                        .build());
    }

    private Optional<String> extractConstraintName(Throwable ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof io.r2dbc.spi.R2dbcDataIntegrityViolationException r2dbcEx
                    && "23505".equals(r2dbcEx.getSqlState())) {
                String msg = r2dbcEx.getMessage();
                Matcher matcher = CONSTRAINT_PATTERN.matcher(msg);
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
                log.warn("Failed to parse constraint name from R2DBC error message: {}", msg);
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }
}
