package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.RetryOutboxEventResponse;
import ru.taska.domain.OutboxEventSnapshot;

/**
 * Маппер административного retry outbox-событий.
 */
@Component
public class OutboxRetryMapper {

    /**
     * Преобразует snapshot outbox-события в gRPC-ответ.
     *
     * @param snapshot состояние outbox-события после retry
     * @return gRPC-ответ с состоянием события
     */
    public RetryOutboxEventResponse toRetryOutboxEventResponse(
            OutboxEventSnapshot snapshot
    ) {
        return RetryOutboxEventResponse.newBuilder()
                .setEventId(snapshot.id().toString())
                .setStatus(snapshot.status().name())
                .setAttempts(snapshot.attempts())
                .build();
    }
}