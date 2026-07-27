package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueAttachment;

import java.util.UUID;

public interface IssueAttachmentRepository extends ReactiveCrudRepository<IssueAttachment, UUID> {

    Flux<IssueAttachment> findByIssueIdAndDeletedAtIsNull(UUID issueId);

    Mono<IssueAttachment> findByIdAndDeletedAtIsNull(UUID id);
}
