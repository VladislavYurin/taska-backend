package ru.taska.util;

import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.domain.GlobalRole;

/**
 * Утилитный класс для конвертации между доменным перечислением {@link GlobalRole}
 * и его protobuf-представлением {@link GlobalRoleProto}.
 *
 * <p>Используется на границе gRPC-слоя для маппинга ролей пользователя
 * между внутренней доменной моделью и контрактом сервиса.</p>
 */
public final class GlobalRoleMapper {
    private GlobalRoleMapper() {}

    /**
     * Преобразует protobuf-представление глобальной роли в доменное значение.
     *
     * @param protoGobalRole глобальная роль в формате protobuf
     * @return соответствующее доменное значение {@link GlobalRole}
     */
    public static GlobalRole toGlobalRole(GlobalRoleProto protoGobalRole) {
        return switch (protoGobalRole) {
            case GLOBAL_ROLE_GLOBAL_ADMIN -> GlobalRole.GLOBAL_ADMIN;
            case GLOBAL_ROLE_USER -> GlobalRole.USER;
            default -> GlobalRole.UNSPECIFIED;
        };
    }

    /**
     * Преобразует доменное значение глобальной роли в protobuf-представление.
     *
     * @param globalRole доменное значение глобальной роли
     * @return соответствующее protobuf-представление {@link GlobalRoleProto}
     */
    public static GlobalRoleProto toProtoGlobalRole (GlobalRole globalRole) {
        return switch (globalRole) {
            case GLOBAL_ADMIN -> GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN;
            case USER -> GlobalRoleProto.GLOBAL_ROLE_USER;
            default -> GlobalRoleProto.GLOBAL_ROLE_UNSPECIFIED;
        };
    }
}
