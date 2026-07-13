package ru.taska.repository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.User;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    //Mono<User> findByEmail(String mail);

    @Query("SELECT * FROM taska.users WHERE email = :email")
    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByLogin(String login);
}