package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequest;
import ru.taska.config.props.GrpcClientProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.ReactorAdminUserManagementServiceGrpc;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.mapper.AdminUserManagementMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с {@code auth-service} по управлению статусами пользователей.
 *
 * <p>Данный клиент используется административным сервисом (admin-service) как фасад
 * для вызова операций управления пользователями, реализованных в auth-service.
 *
 * <p>Архитектурное обоснование:
 * <ul>
 *   <li><b>Разделение ответственности:</b> admin-service выступает как шлюз для административных операций,
 *   а auth-service является источником истины для данных пользователей (User, Credential).</li>
 *   <li><b>Сервисная граница:</b> все операции, изменяющие статус пользователя или его учётные данные,
 *   выполняются в auth-service, который владеет этими сущностями.</li>
 * </ul>
 * </p>
 *
 * <p>Предоставляемые операции:
 * <ul>
 *   <li>{@link #blockUser(String, String, UserStatusRequestDto)} — блокировка пользователя</li>
 *   <li>{@link #unblockUser(String, String, UserStatusRequestDto)} — разблокировка пользователя</li>
 *   <li>{@link #resetCredentialLockout(String, String, UserStatusRequestDto)} — сброс блокировки учётных данных</li>
 * </ul>
 * </p>
 *
 * @see ru.taska.api.auth.admin.management.v1.ReactorAdminUserManagementServiceGrpc
 * @see ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto
 * @see ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminUserManagementServiceClient {

    private final ReactorAdminUserManagementServiceGrpc.ReactorAdminUserManagementServiceStub userManagementServiceStub;
    private final AdminUserManagementMapper managementMapper;
    private final GrpcClientProperties properties;

    /**
     * Выполняет блокировку пользователя.
     *
     * <p>Изменяет статус пользователя на {@code BLOCKED}.
     * Допустимые переходы: {@code ACTIVE → BLOCKED}, {@code INVITED → BLOCKED}.</p>
     *
     * <p>После успешной блокировки в auth-service создаётся Outbox-событие {@code USER_BLOCKED}
     * для уведомления других сервисов.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя (admin-service)
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     */
    public Mono<UserStatusResponseDto> blockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        log.info("[{}] Calling blockUser", requestId);

        BlockUserRequest grpcRequest = managementMapper.toAuthProtoBlockRequest(requestId,nodeId,requestDto);

        return dynamicStub().blockUser(grpcRequest)
                .map(managementMapper::toUserStatusResponseDto);
    }

    /**
     * Выполняет разблокировку пользователя.
     *
     * <p>Изменяет статус пользователя с {@code BLOCKED} на {@code ACTIVE}.
     * Допустимый переход: {@code BLOCKED → ACTIVE}.</p>
     *
     * <p>После успешной разблокировки в auth-service создаётся Outbox-событие {@code USER_UNBLOCKED}
     * для уведомления других сервисов.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя (admin-service)
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     */
    public Mono<UserStatusResponseDto> unblockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        log.info("[{}] Calling unblockUser", requestId);

        UnblockUserRequest grpcRequest = managementMapper.toAuthProtoUnblockRequest(requestId,nodeId,requestDto);

        return dynamicStub().unblockUser(grpcRequest)
                .map(managementMapper::toUserStatusResponseDto);
    }

    /**
     * Сбрасывает блокировку учётных данных пользователя после неудачных попыток входа.
     *
     * <p>Операция выполняется только для пользователей в статусе {@code LOCKED}:</p>
     *
     * <p>Аудит выполняется на уровне admin-service через {@code AuditService}.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя (admin-service)
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     */
    public Mono<UserStatusResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        log.info("[{}][{}] Calling resetCredentialLockout", requestId, nodeId);

        ResetCredentialLockoutAuthRequest grpcRequest = managementMapper.toAuthProtoResetRequest(requestId,nodeId,requestDto);

        return dynamicStub().resetCredentialLockout(grpcRequest)
                .map(managementMapper::toUserStatusResponseDto);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorAdminUserManagementServiceGrpc.ReactorAdminUserManagementServiceStub dynamicStub() {
        return userManagementServiceStub.withDeadlineAfter(
                properties.authService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }
}
