package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.NotificationPreference;

import java.util.UUID;

public interface NotificationPreferenceRepository extends ReactiveCrudRepository<NotificationPreference, UUID> {
    Mono <NotificationPreference> findByUserId(UUID userId);
}
