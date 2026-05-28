package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AuthResponseDto;
import ru.taska.dto.PasswordByTokenResponseDto;

/**
 * Сервис аутентификации и управления сессиями.
 * <p>Предоставляет методы для входа по email/паролю и обновления токенов по refresh-токену.</p>
 */
public interface AuthService {

    /**
     * Выполняет вход пользователя по email и паролю.
     *
     * @param email    email пользователя
     * @param password пароль в открытом виде
     * @return {@link Mono}, содержащий {@link AuthResponseDto} с токенами
     * @throws DomainException если пользователь не найден, заблокирован, не активирован,
     *                         пароль неверен, превышен лимит попыток и т.д.
     */
    Mono<AuthResponseDto> login(String email, String password);

    /**
     * Обновляет пару токенов с использованием валидного refresh-токена.
     *
     * @param refreshToken сырой refresh-токен
     * @return {@link Mono}, содержащий новую пару токенов
     * @throws DomainException если refresh-токен недействителен, истёк или пользователь не найден
     */
    Mono<AuthResponseDto> refresh(String refreshToken);

    Mono<PasswordByTokenResponseDto> setPasswordByToken(String token, String newPassword);
}
