package ru.taska.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PasswordByTokenResponseDto {
    private final String login;
}
