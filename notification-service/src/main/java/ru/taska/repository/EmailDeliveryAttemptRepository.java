package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import ru.taska.domain.EmailAttemptStatus;
import ru.taska.domain.EmailDeliveryAttempt;

import java.util.UUID;

public interface EmailDeliveryAttemptRepository extends ReactiveCrudRepository<EmailDeliveryAttempt, UUID> {

    Flux<EmailDeliveryAttempt> findByStatusOrderByNextRetryAtAsc(EmailAttemptStatus status);
}
