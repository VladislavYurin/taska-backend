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
            a.id AS "avatar.id",
            a.user_id AS "avatar.userId",
            a.object_key AS "avatar.objectKey",
            a.file_name AS "avatar.fileName",
            a.content_type AS "avatar.contentType",
            a.size_bytes AS "avatar.sizeBytes",
            a.created_at AS "avatar.createdAt"
        FROM taska.users u
        LEFT JOIN taska.user_avatars a ON u.id = a.user_id
        WHERE u.id IN (:userIds)
    """)
    Flux<UserDetailsDto> findUsersWithAvatars(@Param("userIds") Collection<UUID> userIds);
}