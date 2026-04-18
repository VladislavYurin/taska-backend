package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.entity.Credential;
import ru.taska.entity.HashingAlgorithm;

public interface PasswordHashService {

    String encode(String rawPassword, HashingAlgorithm algorithm);

    Mono<Boolean> matches(Credential credential, String rawPassword);
}