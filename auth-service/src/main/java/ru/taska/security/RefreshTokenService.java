package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.entity.User;
import ru.taska.dto.RefreshTokenResponseDto;

public interface RefreshTokenService {
    Mono<String> createRefreshToken(User user);

    Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken);
}
