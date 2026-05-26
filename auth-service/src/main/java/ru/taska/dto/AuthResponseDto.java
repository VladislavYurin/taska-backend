package ru.taska.dto;

import lombok.Builder;
import lombok.Value;

/**
 * DTO ответа при аутентификации.
 * <p>Содержит токены доступа и обновления, а также время жизни access-токена в секундах.</p>
 */
@Value
@Builder
public class AuthResponseDto {
    String accessToken;
    String refreshToken;
    Long expiresIn;
}
