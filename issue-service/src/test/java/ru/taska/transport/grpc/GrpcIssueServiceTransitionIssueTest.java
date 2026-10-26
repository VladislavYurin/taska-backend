package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.TransitionIssueRequestBody;
import ru.taska.domain.dto.IssueWithHistory;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.transition.IssueTransitionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrpcIssueServiceTransitionIssueTest {
    @Mock private IssueTransitionService transitions;
    @Mock private IssueMapper mapper;
    @InjectMocks private GrpcIssueService service;

    private static final UUID ISSUE = UUID.randomUUID();
    private static final UUID TRANSITION = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void optionalVersionIsForwardedAndOldClientStaysAbsent() {
        for (Integer expected : new Integer[]{null, 3}) {
            IssueWithHistory result = mock(IssueWithHistory.class, RETURNS_DEEP_STUBS);
            when(transitions.transitionIssue("request", "node", ISSUE, TRANSITION, ACTOR, "", expected))
                    .thenReturn(Mono.just(result));
            when(mapper.toIssueDetailsProto(result)).thenReturn(IssueWithHistoryResponse.getDefaultInstance());
            StepVerifier.create(service.transitionIssue(Mono.just(request(expected))))
                    .expectNext(IssueWithHistoryResponse.getDefaultInstance()).verifyComplete();
            verify(transitions).transitionIssue("request", "node", ISSUE, TRANSITION, ACTOR, "", expected);
        }
    }

    @Test
    void nonPositiveVersionIsRejectedBeforeServiceCall() {
        for (int invalid : new int[]{0, -1}) {
            StepVerifier.create(service.transitionIssue(Mono.just(request(invalid))))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(StatusRuntimeException.class);
                        assertThat(((StatusRuntimeException) error).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    }).verify();
        }
        verifyNoInteractions(transitions, mapper);
    }

    private TransitionIssueRequest request(Integer version) {
        var body = TransitionIssueRequestBody.newBuilder().setIssueId(ISSUE.toString())
                .setTransitionId(TRANSITION.toString()).setActorUserId(ACTOR.toString());
        if (version != null) body.setExpectedVersion(version);
        return TransitionIssueRequest.newBuilder().setHeader(Header.newBuilder().setRequestId("request").setNodeId("node"))
                .setBody(body).build();
    }
}
