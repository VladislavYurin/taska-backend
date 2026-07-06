package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;

public interface UserService {
    Mono<ValidateAccessTokenResponseDto> getMyInfo(GatewayContext context);
}
