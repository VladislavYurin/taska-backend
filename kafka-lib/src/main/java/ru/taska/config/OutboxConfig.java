package ru.taska.config;

import java.time.Duration;

/**
 * Общий конфигурационный интерфейс outbox
 * Каждый сервис реализует интерфейс, предоставляя свои значения
 */
public interface OutboxConfig {
    /**
     * Название топика для событий этого сервиса
     */
    String getTopic();

    /**
     * Интервал опроса новых событий
     */
    Duration getPollingInterval();

    /**
     * Интервал восстановления "застрявших" событий
     */
    Duration getRecoveryInterval();

    /**
     * Размер пачки для обработки
     */
    int getBatchSize();

    /**
     * Максимальное количество попыток публикации
     */
    int getMaxAttempts();

    /**
     * Таймаут, после которого событие PROCESSING считается "застрявшим"
     */
    Duration getProcessingTimeout();
}
