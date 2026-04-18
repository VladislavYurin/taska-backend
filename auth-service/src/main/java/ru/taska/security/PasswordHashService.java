package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.entity.Credential;
import ru.taska.entity.HashingAlgorithm;

/**
 * Сервис для хэширования и верификации паролей и других секретов.
 *
 * <p>Предоставляет методы для безопасного хранения паролей с использованием
 * различных алгоритмов хэширования (BCrypt, Argon2). Сырые пароли никогда
 * не сохраняются в БД — только их хэши.</p>
 *
 * <p>Поддерживаемые алгоритмы хэширования:</p>
 * <ul>
 *     <li>{@link HashingAlgorithm#BCRYPT} — стандартный алгоритм с настраиваемой стойкостью (cost factor)</li>
 *     <li>{@link HashingAlgorithm#ARGON2} — современный алгоритм, устойчивый к атакам с использованием GPU и ASIC</li>
 * </ul>
 *
 * <p>Алгоритм выбирается на уровне учётных данных {@link Credential}
 * и сохраняется в поле {@code algo} для возможности корректной верификации.</p>
 *
 * @see Credential
 * @see HashingAlgorithm
 * @see org.springframework.security.crypto.password.PasswordEncoder
 */
public interface PasswordHashService {

    /**
     * Выполняет хэширование сырого пароля с использованием указанного алгоритма.
     *
     * <p>Алгоритм автоматически генерирует соль (salt) и включает её в результирующий хэш.
     * Хэш сохраняется в поле {@code secretHash} сущности {@link Credential}.</p>
     *
     * <p>Важно: один и тот же пароль при каждом вызове будет давать РАЗНЫЙ хэш из-за случайной соли.
     * Для верификации используйте {@link #matches(Credential, String)}.</p>
     *
     * @param rawPassword пароль в открытом виде (не может быть null или пустым)
     * @param algorithm алгоритм хэширования (BCRYPT или ARGON2)
     * @return хэш пароля в формате, совместимом с выбранным алгоритмом
     * @throws exception.DomainException если пароль пустой или произошла ошибка при хэшировании
     */
    String encode(String rawPassword, HashingAlgorithm algorithm);

    /**
     * Проверяет соответствие сырого пароля сохранённому хэшу.
     *
     * <p>Метод автоматически определяет алгоритм хэширования из переданных учётных данных
     * и использует соответствующий {@code PasswordEncoder} для верификации.</p>
     *
     * <p>Алгоритм работы:</p>
     * <ol>
     *     <li>Извлекает {@code algorithm} из {@code credential.getAlgo()}</li>
     *     <li>Извлекает {@code secretHash} из {@code credential.getSecretHash()}</li>
     *     <li>Выполняет верификацию с учётом встроенной в хэш соли</li>
     *     <li>Возвращает {@code true} только при полном совпадении</li>
     * </ol>
     *
     * <p>Метод является реактивным и возвращает {@link Mono} для асинхронного выполнения,
     * что важно при работе с потенциально затратными криптографическими операциями.</p>
     *
     * @param credential учётные данные, содержащие {@code secretHash} и {@code algo}
     * @param rawPassword пароль в открытом виде для проверки
     * @return {@link Mono}, содержащий {@code true}, если пароль совпадает с хэшем,
     *         и {@code false} в противном случае
     * @throws exception.DomainException если произошла ошибка при верификации
     *                                   (например, неподдерживаемый алгоритм)
     */
    Mono<Boolean> matches(Credential credential, String rawPassword);
}