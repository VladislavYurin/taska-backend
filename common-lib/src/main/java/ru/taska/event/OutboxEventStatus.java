package ru.taska.event;


/**
 * Статус для отправки события из outbox_event в kafka
 */
public enum OutboxEventStatus {

    /**
     * Событие еще не отправлено
     */
    NEW,

    /**
     * Событие обрабатывается scheduler'ом
     */
    PROCESSING,

    /**
     * Событие успешно опубликовано
     */
    PUBLISHED,

    /**
     * Превышено количество попыток опубликовать событие
     */
    FAILED

}
