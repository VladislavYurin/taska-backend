package ru.taska.dto;

import lombok.Builder;
import lombok.Value;

/**
 * DTO профиля пользователя.
 */
@Value
@Builder
public class UserProfileDto {

    /**
     * Данные об аватаре. {@code null}, если аватар не установлен.
     */
    AvatarDto avatar;
}
