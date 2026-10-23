package ru.taska.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.repository.IssueRepository;

@ExtendWith(MockitoExtension.class)
class GrpcIssueTypeCountsTest {
    @Mock IssueRepository repository;
    @InjectMocks GrpcIssueService grpc;
    private final UUID project = UUID.randomUUID();

    @Test
    void filteredCountAcknowledgesAppliedFiltersEvenWhenNoIssuesExist() {
        when(repository.countProjectIssueTypesBatch(List.of(project), "REQUEST", true)).thenReturn(Flux.empty());
        StepVerifier.create(grpc.countProjectIssuesBatchInternal(Mono.just(CountProjectIssuesBatchInternalRequest.newBuilder()
                        .addProjectIds(project.toString()).setIssueTypeKey("REQUEST").setIncludeDeleted(true).build())))
                .assertNext(response -> {
                    assertThat(response.getItemsList()).isEmpty();
                    assertThat(response.getFiltersApplied()).isTrue();
                }).verifyComplete();
        verify(repository, never()).countProjectsBatch(any());
    }

    @Test
    void originalProjectStatsStillUseActiveUnfilteredCount() {
        when(repository.countProjectsBatch(List.of(project))).thenReturn(Flux.empty());
        StepVerifier.create(grpc.countProjectIssuesBatchInternal(Mono.just(CountProjectIssuesBatchInternalRequest.newBuilder()
                        .addProjectIds(project.toString()).build())))
                .assertNext(response -> assertThat(response.getItemsList()).isEmpty()).verifyComplete();
        verify(repository, never()).countProjectIssueTypesBatch(any(), any(), anyBoolean());
    }
}
