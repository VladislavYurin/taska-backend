package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueAttachment;

import java.time.Instant;
import java.util.UUID;

public interface IssueAttachmentRepository extends ReactiveCrudRepository<IssueAttachment, UUID> {

    Flux<IssueAttachment> findAllByIssueIdAndDeletedAtIsNull(UUID issueId);

    Mono<IssueAttachment> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Атомарно выполняет мягкое удаление вложения.
     * Устанавливает deleted_at только если запись ещё не удалена.
     *
     * @param id        идентификатор вложения.
     * @param deletedAt временная метка удаления.
     * @return Mono с количеством обновлённых строк (1 — удалено, 0 — уже было удалено).
     */
    @Query("UPDATE taska.issue_attachments SET deleted_at = :deletedAt " +
            "WHERE id = :id AND deleted_at IS NULL")
    Mono<Long> softDelete(UUID id, Instant deletedAt);
}
