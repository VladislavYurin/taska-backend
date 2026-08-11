package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;

/**
 * Сервис для административного управления статусами пользователей.
 *
 * <p>Предоставляет операции для блокировки, разблокировки и сброса блокировки
 * учётных данных пользователей. Все операции требуют прав GLOBAL_ADMIN
 * и обязательного указания причины действия для аудита.</p>
 *
 * <p>Операции сервиса:</p>
 * <ul>
 *   <li><b>blockUser</b> — блокировка пользователя (статус → BLOCKED)</li>
 *   <li><b>unblockUser</b> — разблокировка пользователя (BLOCKED → ACTIVE)</li>
 *   <li><b>resetCredentialLockout</b> — сброс блокировки учётных данных (LOCKED → ACTIVE)</li>
 * </ul>
 *
 * <p>Все операции являются транзакционными и сохраняют аудит-события.</p>
 *
 * @see ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto
 * @see ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto
 * @see ru.taska.entity.UserStatus
 */
public interface AdminUserManagementService {

    /**
     * Блокирует пользователя.
     *
     * <p>Изменяет статус пользователя на {@code BLOCKED}.
     * Допустимые переходы: {@code ACTIVE → BLOCKED}, {@code INVITED → BLOCKED}.
     * Запрещено блокировать последнего активного {@code GLOBAL_ADMIN}.</p>
     *
     * <p>После успешной блокировки создаётся Outbox-событие {@code USER_BLOCKED}
     * для отправки уведомлений в другие сервисы.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     * @throws ru.taska.exception.DomainException если пользователь не найден,
     *                                            статус не позволяет выполнить операцию,
     *                                            или это последний активный GLOBAL_ADMIN
     */
    Mono<UserStatusResponseDto> blockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );

    /**
     * Разблокирует пользователя.
     *
     * <p>Изменяет статус пользователя с {@code BLOCKED} на {@code ACTIVE}.
     * Допустимый переход: {@code BLOCKED → ACTIVE}.
     * Невозможно активировать пользователя в статусе {@code INVITED} через этот метод.</p>
     *
     * <p>После успешной разблокировки создаётся Outbox-событие {@code USER_UNBLOCKED}
     * для отправки уведомлений в другие сервисы.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     * @throws ru.taska.exception.DomainException если пользователь не найден
     *                                            или статус не {@code BLOCKED}
     */
    Mono<UserStatusResponseDto> unblockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );

    /**
     * Сбрасывает блокировку учётных данных пользователя после неудачных попыток входа.
     *
     * <p>Операция выполняется только для пользователей в статусе {@code LOCKED}:
     * <ul>
     *   <li>Изменяет статус пользователя: {@code LOCKED → ACTIVE}</li>
     *   <li>Сбрасывает счётчик неудачных попыток входа: {@code failed_attempts = 0}</li>
     *   <li>Очищает время блокировки: {@code locked_until = null}</li>
     *   <li>Очищает время последней неудачной попытки: {@code last_failed_at = null}</li>
     * </ul>
     * </p>
     *
     * <p><b>Важно:</b> операция НЕ изменяет {@code secret_hash}, {@code algo} и пароль пользователя.
     * Outbox-событие для этой операции НЕ создаётся.</p>
     *
     * <p>Аудит выполняется на уровне admin-service через {@code AuditService}.</p>
     *
     * @param requestId  уникальный идентификатор запроса для сквозной трассировки
     * @param nodeId     идентификатор узла-отправителя
     * @param requestDto DTO с данными запроса (targetUserId, actorUserId, reason)
     * @return {@link Mono}, содержащий {@link UserStatusResponseDto} с результатом операции
     * @throws ru.taska.exception.DomainException если пользователь не найден
     *                                            или статус не {@code LOCKED}
     */
    Mono<UserStatusResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );
}