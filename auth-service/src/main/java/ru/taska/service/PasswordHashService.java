package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Credential;
import ru.taska.domain.HashingAlgorithm;

public interface PasswordHashService {

    String encode(String rawPassword, HashingAlgorithm algorithm);

    Mono<Boolean> matches(Credential credential, String rawPassword);
}