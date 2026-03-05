package ru.taska.domain;

/**
 * Статус попытки доставки email.
 */
public enum EmailAttemptStatus {

    /**
     * Попытка ещё не выполнена или ожидает повтора.
     */
    PENDING,

    /**
     * Письмо успешно доставлено.
     */
    SENT,

    /**
     * Попытка завершилась ошибкой.
     */
    FAILED
}