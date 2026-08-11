package ru.taska.util;

import ru.taska.api.common.v1.UserStatus;
/**
 * Маппер для преобразования между entity и proto статусами пользователя.
 *
 * <p>Используется в сервисах для конвертации статусов при формировании
 * {@link ru.taska.api.common.v1.UserContext}.</p>
 */
public final class UserStatusMapper {

    private UserStatusMapper() {}

    /**
     * Преобразует entity статус в proto статус.
     *
     * @param entityStatus статус из БД
     * @return proto статус
     */
    public static UserStatus toProtoStatus(ru.taska.entity.UserStatus entityStatus) {
        return switch (entityStatus) {
            case INVITED -> UserStatus.USER_STATUS_INVITED;
            case ACTIVE -> UserStatus.USER_STATUS_ACTIVE;
            case BLOCKED -> UserStatus.USER_STATUS_BLOCKED;
            case LOCKED -> UserStatus.USER_STATUS_LOCKED;
        };
    }

    /**
     * Преобразует proto статус в entity статус.
     *
     * @param protoStatus proto статус
     * @return entity статус
     */
    public static ru.taska.entity.UserStatus toEntityStatus(UserStatus protoStatus) {
        return switch (protoStatus) {
            case USER_STATUS_ACTIVE -> ru.taska.entity.UserStatus.ACTIVE;
            case USER_STATUS_BLOCKED -> ru.taska.entity.UserStatus.BLOCKED;
            case USER_STATUS_LOCKED -> ru.taska.entity.UserStatus.LOCKED;
            default -> ru.taska.entity.UserStatus.INVITED;
        };
    }
}