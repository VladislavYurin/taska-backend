package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.User;

public interface JwtService {
    Mono<String> generateAccessToken(User user);
}
