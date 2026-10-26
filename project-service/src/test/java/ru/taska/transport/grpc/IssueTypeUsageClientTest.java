package ru.taska.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.transport.grpc.client.GrpcIssueServiceClient;

class IssueTypeUsageClientTest {
    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub stub = mock(ReactorIssueServiceGrpc.ReactorIssueServiceStub.class);
    private final UUID project = UUID.randomUUID();
    private GrpcIssueServiceClient client;

    @BeforeEach
    void setup() {
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        client = new GrpcIssueServiceClient(stub, new GrpcClientProperties(null,
                new GrpcClientProperties.ExternalService("localhost", 1, Duration.ofSeconds(1)), null));
    }

    @Test
    void asksForExactTypeExcludingDeletedIssues() {
        when(stub.countProjectIssuesBatchInternal(any(CountProjectIssuesBatchInternalRequest.class)))
                .thenReturn(Mono.just(CountProjectIssuesBatchInternalResponse.newBuilder().setFiltersApplied(true)
                        .addItems(ProjectIssueCount.newBuilder().setProjectId(project.toString()).setIssueCount(3)).build()));
        StepVerifier.create(client.countIssueTypeUsage(project, "REQUEST", "r", "n")).expectNext(3L).verifyComplete();
        var captor = ArgumentCaptor.forClass(CountProjectIssuesBatchInternalRequest.class);
        verify(stub).countProjectIssuesBatchInternal(captor.capture());
        assertThat(captor.getValue().getIssueTypeKey()).isEqualTo("REQUEST");
        assertThat(captor.getValue().getIncludeDeleted()).isFalse();
        assertThat(captor.getValue().getProjectIdsList()).containsExactly(project.toString());
    }

    @Test
    void oldServerCannotSilentlyAuthorizeDestructiveOperation() {
        when(stub.countProjectIssuesBatchInternal(any(CountProjectIssuesBatchInternalRequest.class)))
                .thenReturn(Mono.just(CountProjectIssuesBatchInternalResponse.getDefaultInstance()));
        StepVerifier.create(client.countIssueTypeUsage(project, "BUG", "r", "n"))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getStatus())
                        .isEqualTo(DomainStatus.UNAVAILABLE)).verify();
    }
}
