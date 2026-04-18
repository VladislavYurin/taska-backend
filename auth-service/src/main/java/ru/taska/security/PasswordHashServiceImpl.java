package ru.taska.security;

import exception.DomainException;
import exception.DomainStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.entity.Credential;
import ru.taska.entity.HashingAlgorithm;

@Service
@Slf4j
@RequiredArgsConstructor
public class PasswordHashServiceImpl implements PasswordHashService {

    private static final String FIXED_BCRYPT_SALT = "$2a$10$FixedSalt5ghfghtyGE45nfh7fsfeobx";

    private final PasswordEncoder bCryptEncoder = new PasswordEncoder() {
        @Override
        public String encode(@Nullable CharSequence rawPassword) {
            if (rawPassword == null) {
                throw new DomainException(DomainStatus.INVALID_ARGUMENT, "rawPassword cannot be null");
            }
            return BCrypt.hashpw(rawPassword.toString(), FIXED_BCRYPT_SALT);
        }

        @Override
        public boolean matches(@Nullable CharSequence rawPassword, @Nullable String encodedPassword) {
            if (rawPassword == null) {
                throw new DomainException(DomainStatus.INVALID_ARGUMENT, "rawPassword cannot be null");
            }
            if (encodedPassword == null) {
                throw new DomainException(DomainStatus.INVALID_ARGUMENT, "encodedPassword cannot be null");
            }
            return BCrypt.checkpw(rawPassword.toString(), encodedPassword);
        }
    };

    private final Argon2PasswordEncoder argon2Encoder = new Argon2PasswordEncoder(
            16, 32, 1, 4096, 3);

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