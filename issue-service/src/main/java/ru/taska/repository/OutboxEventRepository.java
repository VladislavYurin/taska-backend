package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import ru.taska.domain.OutboxEvent;

import java.util.UUID;

public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEvent, UUID> {
}
