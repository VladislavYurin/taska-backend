package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcBadGrammarException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueRequestBody;
import ru.taska.api.issue.v1.PatchIssueResponse;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.stream.Stream;

/**
 * Тесты {@link GrpcIssueServiceAdapter#patchIssue}: делегирование в {@link GrpcIssueService}
 * и преобразование ошибок в gRPC-статусы.
 */
@ExtendWith(MockitoExtension.class)
class GrpcIssueServiceAdapterPatchIssueTest {

    private static final PatchIssueRequest REQUEST = PatchIssueRequest.newBuilder()
            .setHeader(Header.newBuilder()
                    .setRequestId("req-001")
                    .setNodeId("issue-node")
                    .build())
            .setBody(PatchIssueRequestBody.newBuilder()
                    .setIssueId("00000000-0000-0000-0000-000000000001")
                    .setActorUserId("00000000-0000-0000-0000-000000000003")
                    .setVersion(3)
                    .build())
            .build();

    @Mock
    private GrpcIssueService grpcIssueService;

    @Mock
    private GrpcIssueCommentService grpcIssueCommentService;

    @Mock
    private GrpcIssueLinkService grpcIssueLinkService;

    @Mock
    private GrpcIssueWatcherService grpcIssueWatcherService;

    @InjectMocks
    private GrpcIssueServiceAdapter adapter;

    @Test
    @DisplayName("Успешный ответ возвращается без изменений")
    void patchIssue_shouldReturnResponse_whenServiceSucceeds() {
        PatchIssueResponse response = PatchIssueResponse.newBuilder()
                .setVersionConflict(true)
                .build();
        Mockito.when(grpcIssueService.patchIssue(Mockito.any())).thenReturn(Mono.just(response));

        StepVerifier.create(adapter.patchIssue(Mono.just(REQUEST)))
                .expectNext(response)
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("errorMappings")
    @DisplayName("Ошибки из GrpcIssueService преобразуются в gRPC-статусы")
    void patchIssue_shouldMapErrorToGrpcStatus(String name, Throwable serviceError, Status.Code expectedCode) {
        Mockito.when(grpcIssueService.patchIssue(Mockito.any())).thenReturn(Mono.error(serviceError));

        StepVerifier.create(adapter.patchIssue(Mono.just(REQUEST)))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(StatusRuntimeException.class);
                    Assertions.assertThat(((StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(expectedCode);
                })
                .verify();
    }

    private static Stream<Arguments> errorMappings() {
        return Stream.of(
                Arguments.of("validation error",
                        Status.INVALID_ARGUMENT.withDescription("body.issueId must be a valid UUID").asRuntimeException(),
                        Status.Code.INVALID_ARGUMENT),
                Arguments.of("DomainException NOT_FOUND",
                        new DomainException(DomainStatus.NOT_FOUND, "Issue not found"),
                        Status.Code.NOT_FOUND),
                Arguments.of("DomainException PERMISSION_DENIED",
                        new DomainException(DomainStatus.PERMISSION_DENIED, "Access denied"),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of("DomainException FAILED_PRECONDITION",
                        new DomainException(DomainStatus.FAILED_PRECONDITION, "Start date must not be after Due date"),
                        Status.Code.FAILED_PRECONDITION),
                Arguments.of("R2dbcBadGrammarException",
                        new R2dbcBadGrammarException("Table not found"),
                        Status.Code.UNAVAILABLE),
                Arguments.of("TransactionException",
                        new CannotCreateTransactionException("Transaction failed"),
                        Status.Code.UNAVAILABLE),
                Arguments.of("RuntimeException",
                        new RuntimeException("Unexpected error"),
                        Status.Code.INTERNAL)
        );
    }
}
