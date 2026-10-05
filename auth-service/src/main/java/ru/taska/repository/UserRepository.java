package ru.taska.repository;

import java.util.Collection;
import java.util.UUID;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.UserDetailsDto;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    @Query("SELECT * FROM taska.users WHERE email = :email")
    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByLogin(String login);

    Mono<Long> countByGlobalRoleAndStatus(GlobalRole globalRole, UserStatus status);

    @Query("""
        SELECT 
            u.id AS user_id, 
            u.display_name AS display_name, 
            u.email AS email, 
            a.user_id AS avatar_user_id,
            a.object_key AS avatar_object_key,
            a.file_name AS avatar_file_name,
            a.content_type AS avatar_content_type,
            a.size_bytes AS avatar_size_bytes,
            a.created_at AS avatar_created_at
        FROM taska.users u
        LEFT JOIN taska.user_avatars a ON u.id = a.user_id
        WHERE u.id IN (:userIds)
        ORDER BY u.id ASC
    """)
    Flux<UserDetailsDto> findUsersWithAvatars(@Param("userIds") Collection<UUID> userIds);

    /**
     * Атомарно снимает LOCKED, если окно lockedUntil истекло.
     * <p>Условие WHERE проверяется под row-lock'ом самого UPDATE, поэтому
     * параллельные вызовы безопасны: второй вернёт Empty.</p>
     *
     * @param userId id пользователя
     * @return {@link User} обновлённого пользователя, либо {@code Empty}, если локаут не был активен
     */
    @Query("""
    UPDATE taska.users
       SET status = 'ACTIVE',
           locked_until = NULL,
           updated_at = now()
     WHERE id = :userId
       AND status = 'LOCKED'
       AND (locked_until IS NULL OR locked_until < now())
    RETURNING *
""")
    Mono<User> unlockIfExpired(@Param("userId") UUID userId);
}