package ru.taska.transport.grpc.workflow;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.TransitionViolation;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.api.workflow.v1.ValidateTransitionResponseBody;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;

import java.util.List;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class IssueTransitionValidatorTest {

    @Mock
    private GrpcWorkflowServiceClient client;

    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private IssueTransitionValidator validator;

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-node";
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TRANSITION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String PAYLOAD = "some-kind-of-payload";
    private static final ru.taska.api.workflow.v1.IssueStatus TARGET_STATUS = ru.taska.api.workflow.v1.IssueStatus.ISSUE_STATUS_IN_PROGRESS;

    private static Header header;
    private static Issue issue;

    @BeforeAll
    static void init() {
        header = Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();

        issue = Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .build();
    }

    @Test
    @DisplayName("Валидация перехода должна пройти успешно и вернуть правильный статус")
    void validateTransition_shouldCompleteSuccessfully_whenValidationIsAllowed() {
        var response = ValidateTransitionResponse.newBuilder()
                .setHeader(header)
                .setBody(
                        ValidateTransitionResponseBody.newBuilder()
                                .setIsValid(true)
                                .setToStatusKey(TARGET_STATUS)
                                .build()
                )
                .build();

        Mockito.when(client.validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD))
                .thenReturn(Mono.just(response));

        Mockito.when(issueMapper.toDomainIssueStatus(TARGET_STATUS))
                .thenReturn(IssueStatus.IN_PROGRESS);

        StepVerifier.create(validator.validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD))
                .expectNext(IssueStatus.IN_PROGRESS)
                .verifyComplete();

        Mockito.verify(client).validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD);
        Mockito.verify(issueMapper).toDomainIssueStatus(TARGET_STATUS);
    }

    @Test
    @DisplayName("Должен бросить исключение, если валидация не прошла")
    void validateTransition_ShouldThrowException_whenValidationFailed() {
        List<TransitionViolation> violations = List.of(
                TransitionViolation.newBuilder()
                        .setMessage("first_violation")
                        .build(),

                TransitionViolation.newBuilder()
                        .setMessage("second_violation")
                        .build()
        );

        var expectedViolationMessage = "first_violation; second_violation";

        var response = ValidateTransitionResponse.newBuilder()
                .setHeader(header)
                .setBody(
                        ValidateTransitionResponseBody.newBuilder()
                                .setIsValid(false)
                                .addAllTransitionViolations(violations)
                                .build()
                )
                .build();

        Mockito.when(client.validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD))
                .thenReturn(Mono.just(response));

        StepVerifier.create(validator.validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);

                    var ex = (DomainException) error;

                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.FAILED_PRECONDITION);
                    Assertions.assertThat(ex.getMessage()).isEqualTo(expectedViolationMessage);
                })
                .verify();

        Mockito.verify(client).validateTransition(REQUEST_ID, NODE_ID, issue, TRANSITION_ID, ACTOR_USER_ID, PAYLOAD);
        Mockito.verify(issueMapper, Mockito.never()).toDomainIssueStatus(Mockito.any(ru.taska.api.workflow.v1.IssueStatus.class));
    }

}