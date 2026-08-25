package ru.taska.repository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.GlobalRole;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    @Query("SELECT * FROM taska.users WHERE email = :email")
    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByLogin(String login);

    Mono<Long> countByGlobalRoleAndStatus(GlobalRole globalRole, UserStatus status);
}