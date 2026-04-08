package ru.taska.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AuthResponseDto {
    String accessToken;
    String refreshToken;
    Long expiresIn;
}
