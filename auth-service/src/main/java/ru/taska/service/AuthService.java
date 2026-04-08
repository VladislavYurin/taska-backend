package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AuthResponseDto;

public interface AuthService {

    Mono<AuthResponseDto> login(String email, String password);

    Mono<AuthResponseDto> refresh(String refreshToken);
}
