package ru.taska.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @TrackMetrics будет отдавать в микрометр метрики.
 * <p>Шаблон: [название-сервиса]_[название-метода]_[grpc/rest/...]_[counter/timer]</p>
 * Пример: counter = "issue-service_get-issue_grpc_counter"
 *         timer = "issue-service_get-issue_grpc_timer"
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackMetrics {
    /// Параметры аннотации, записываются как методы
    /// Название метрик: нужно указывать в параметрах аннотации @TrackMetrics
    String counter();
    String timer();

}
