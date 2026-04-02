package ru.taska.repository;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import ru.taska.domain.Notification;

public interface NotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    Flux<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId);
}
