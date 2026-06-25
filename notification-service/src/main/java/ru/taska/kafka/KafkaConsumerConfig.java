package ru.taska.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * Конфигурация Kafka-консьюмера.
 *
 * <p>Включает ручное подтверждение offset (MANUAL_IMMEDIATE) и ограниченный
 * retry через {@link DefaultErrorHandler}. Параметры retry задаются через
 * {@code kafka.consumer.retry.max-attempts} и {@code kafka.consumer.retry.interval}.</p>
 *
 * <p>После исчерпания попыток сообщение отправляется в DLT-топик
 * (например, {@code issue.events.DLT}) </p>
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${kafka.consumer.retry.max-attempts}")
    private long maxAttempts;

    @Value("${kafka.consumer.retry.interval}")
    private Duration retryInterval;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryInterval.toMillis(), maxAttempts));

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
