package ru.taska.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.entity.User;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.trust.GrpcMembershipAuthority;

import java.util.Map;
import java.util.UUID;

/**
 * Класс для проверки пользователя в членстве компании
 */
@Component
@AllArgsConstructor
public class DirectoryMembershipAccess {

    private final GrpcMembershipAuthority grpcMembershipAuthority;

    /**
     * Проверка, что переданный пользователь находится в компании tenantId.
     * @param user пользователь;
     * @param tenantId UUID компании;
     * @return успешное завершение, если пользователь член организации, иначе ошибка.
     */
    public Mono<Void> check(User user, String tenantId) {
        return grpcMembershipAuthority.current(UUID.fromString(tenantId), user.getId())
                .switchIfEmpty(Mono.error(new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "Tenant membership is required",
                        Map.of("reason", "TENANT_MEMBERSHIP_REQUIRED")
                )))
                .then();
    }
}