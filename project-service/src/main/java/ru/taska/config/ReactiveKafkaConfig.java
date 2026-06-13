package ru.taska.config;

import java.util.Map;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

@Configuration
public class ReactiveKafkaConfig {

    @Bean
    public KafkaSender<String, String> kafkaSender(KafkaProperties properties) {
        Map<String, Object> props = properties.buildProducerProperties();
        SenderOptions<String, String> senderOptions = SenderOptions.create(props);

        senderOptions = senderOptions
                .stopOnError(false)
                .maxInFlight(256);

        return KafkaSender.create(senderOptions);
    }
}
