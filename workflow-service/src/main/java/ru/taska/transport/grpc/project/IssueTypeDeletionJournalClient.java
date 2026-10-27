package ru.taska.transport.grpc.project;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.registry.v1.IssueTypeDeletionIntent;
import ru.taska.api.registry.v1.ReactorIssueTypeDeletionJournalGrpc;

@Service
public class IssueTypeDeletionJournalClient {
    private final ReactorIssueTypeDeletionJournalGrpc.ReactorIssueTypeDeletionJournalStub stub;

    public IssueTypeDeletionJournalClient(ReactorProjectServiceGrpc.ReactorProjectServiceStub project) {
        this.stub = ReactorIssueTypeDeletionJournalGrpc.newReactorStub(project.getChannel());
    }

    public Mono<IssueTypeDeletionIntent> get(UUID project, UUID operation) {
        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .getIntent(client.IssueTypeDeletionClients.key(project, operation));
    }
}
