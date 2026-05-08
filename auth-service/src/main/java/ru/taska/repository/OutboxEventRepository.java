package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import ru.taska.domain.OutboxEvent;

import java.util.UUID;

@Repository
public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {

    @Query("SELECT * FROM taska.outbox_events WHERE published_at IS NULL AND attempts < 10 " +
            "ORDER BY created_at ASC LIMIT 100")
    Flux<OutboxEvent> findUnpublishedEvents();
}