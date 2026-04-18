package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.entity.User;

public interface JwtService {
    Mono<String> generateAccessToken(User user);
}
