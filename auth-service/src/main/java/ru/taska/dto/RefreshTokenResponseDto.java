package ru.taska.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.taska.domain.RefreshToken;

@Getter
@AllArgsConstructor
public class RefreshTokenResponseDto {
    private final RefreshToken refreshToken;
    private final String rawToken;
}
