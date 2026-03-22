package ru.taska.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.domain.Credential;
import ru.taska.domain.HashingAlgorithm;

@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordHashService {
    private final BCryptPasswordEncoder bCryptEncoder = new BCryptPasswordEncoder(10);
    private final Argon2PasswordEncoder argon2Encoder = new Argon2PasswordEncoder(
            16,
            32,
            1,
            4096,
            3
    );

    public Mono<Boolean> matches(Credential credential, String rawPassword) {
        return Mono.fromCallable(() -> {
            if (credential.getAlgo() == HashingAlgorithm.BCRYPT) {
                return bCryptEncoder.matches(rawPassword, credential.getSecretHash());
            } else if (credential.getAlgo() == HashingAlgorithm.ARGON2) {
                return argon2Encoder.matches(rawPassword, credential.getSecretHash());
            }
            return false;
        });
    }

    public String encode(String rawPassword, HashingAlgorithm algorithm) {
        if (algorithm == HashingAlgorithm.BCRYPT) {
            return bCryptEncoder.encode(rawPassword);
        } else {
            return argon2Encoder.encode(rawPassword);
        }
    }
}
