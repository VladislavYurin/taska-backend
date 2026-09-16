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
}