package ru.taska.security;


import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.entity.Credential;
import ru.taska.entity.HashingAlgorithm;

@Service
@Slf4j
public class PasswordHashServiceImpl implements PasswordHashService {

    private final PasswordEncoder bCryptEncoder;
    private final PasswordEncoder argon2Encoder;

    public PasswordHashServiceImpl(
            @Qualifier("bcryptEncoder") PasswordEncoder bCryptEncoder,
            @Qualifier("argon2Encoder") PasswordEncoder argon2Encoder
    ) {
        this.bCryptEncoder = bCryptEncoder;
        this.argon2Encoder = argon2Encoder;
    }

    @Override
    public String encode(String rawPassword, HashingAlgorithm algorithm) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Password cannot be null or blank");
        }

        if (algorithm == HashingAlgorithm.BCRYPT) {
            return bCryptEncoder.encode(rawPassword);
        } else {
            return argon2Encoder.encode(rawPassword);
        }
    }

    @Override
    public Mono<Boolean> matches(Credential credential, String rawPassword) {
        return Mono.fromCallable(() -> {
            if (credential.getAlgo() == HashingAlgorithm.BCRYPT) {
                return bCryptEncoder.matches(rawPassword, credential.getSecretHash());
            } else if (credential.getAlgo() == HashingAlgorithm.ARGON2) {
                return argon2Encoder.matches(rawPassword, credential.getSecretHash());
            }
            return false;
        }).onErrorMap(e -> new DomainException(DomainStatus.INTERNAL, "Password verification failed"));
    }
}
