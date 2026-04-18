package ru.taska.entity;

/**
 * Алгоритм хэширования секретов (паролей, токенов).
 *
 * <p>Используется для хранения хэша вместо сырого значения.</p>
 */
public enum HashingAlgorithm {
    BCRYPT,
    ARGON2
}
