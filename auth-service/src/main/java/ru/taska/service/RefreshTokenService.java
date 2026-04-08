package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.User;
import ru.taska.dto.RefreshTokenResponseDto;

public interface RefreshTokenService {
    Mono<String> createRefreshToken(User user);

    Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken);
}
