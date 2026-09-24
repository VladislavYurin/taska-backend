package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcBadGrammarException;
import nullable.NullableField;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.common.v1.NullableDouble;
import ru.taska.api.common.v1.NullableInt32;
import ru.taska.api.common.v1.NullableString;
import ru.taska.api.issue.v1.PatchIssueRequest;
import ru.taska.api.issue.v1.PatchIssueRequestBody;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.PatchIssueResult;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.mapper.LabelMapper;
import ru.taska.service.IssueService;
import ru.taska.service.IssueWatcherService;
import ru.taska.service.LabelService;
import ru.taska.service.patch.IssuePatchService;
import ru.taska.service.transition.IssueTransitionService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Тесты транспортного слоя для {@link GrpcIssueService#patchIssue}:
 * валидация и парсинг proto-запроса в {@link IssuePatch}, делегирование в {@link IssuePatchService}
 * и маппинг {@link PatchIssueResult} в proto-ответ.
 */
@ExtendWith(MockitoExtension.class)
class GrpcIssueServicePatchIssueTest {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-node";
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final int VERSION = 3;

    @Mock
    private IssueService issueService;

    @Mock
    private IssueWatcherService issueWatcherService;

    @Mock
    private IssueTransitionService issueTransitionService;

    @Mock
    private IssuePatchService issuePatchService;

    @Mock
    private LabelService labelService;

    @Mock
    private LabelMapper labelMapper;

    private GrpcIssueService grpcIssueService;

    @BeforeEach
    void setUp() {
        // Реальный маппер: проверяем и конвертацию priority в домен, и сборку PatchIssueResponse
        grpcIssueService = new GrpcIssueService(
                issueService,
                issueWatcherService,
                issueTransitionService,
                issuePatchService,
                new IssueMapper(new ObjectMapper()),
                labelService,
                labelMapper
        );
    }

    @Test
    @DisplayName("Пустой patch: все поля не заданы и передаются в сервис как «не менять»")
    void patchIssue_shouldPassAbsentFields_whenBodyHasNoPatchFields() {
        mockPatchServiceReturns(new PatchIssueResult(issue(), false));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request(UnaryOperator.identity()))))
                .expectNextCount(1)
                .verifyComplete();

        IssuePatch patch = capturePatch();

        Assertions.assertThat(patch.summary()).isEmpty();
        Assertions.assertThat(patch.priority()).isEmpty();
        Assertions.assertThat(patch.description()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.assigneeId()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.storyPoints()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.startDate()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.dueDate()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.originalEstimateMinutes()).isEqualTo(NullableField.absent());
        Assertions.assertThat(patch.remainingEstimateMinutes()).isEqualTo(NullableField.absent());
    }

    @Test
    @DisplayName("Все поля заданы значениями: парсятся в доменные типы и передаются в сервис")
    void patchIssue_shouldParseAllValues_whenAllFieldsSet() {
        mockPatchServiceReturns(new PatchIssueResult(issue(), false));

        PatchIssueRequest request = request(body -> body
                .setSummary("New summary")
                .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_HIGH)
                .setDescription(stringValue("New description"))
                .setAssigneeId(stringValue(ASSIGNEE_ID.toString()))
                .setStoryPoints(NullableDouble.newBuilder().setValue(3.5).build())
                .setStartDate(stringValue("2026-10-01"))
                .setDueDate(stringValue("2026-10-15"))
                .setOriginalEstimateMinutes(NullableInt32.newBuilder().setValue(120).build())
                .setRemainingEstimateMinutes(NullableInt32.newBuilder().setValue(0).build()));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request)))
                .expectNextCount(1)
                .verifyComplete();

        IssuePatch patch = capturePatch();

        Assertions.assertThat(patch.summary()).contains("New summary");
        Assertions.assertThat(patch.priority()).contains(IssuePriority.HIGH);
        Assertions.assertThat(patch.description()).isEqualTo(NullableField.of("New description"));
        Assertions.assertThat(patch.assigneeId()).isEqualTo(NullableField.of(ASSIGNEE_ID));
        Assertions.assertThat(patch.storyPoints()).isEqualTo(NullableField.of(BigDecimal.valueOf(3.5)));
        Assertions.assertThat(patch.startDate()).isEqualTo(NullableField.of(LocalDate.of(2026, 10, 1)));
        Assertions.assertThat(patch.dueDate()).isEqualTo(NullableField.of(LocalDate.of(2026, 10, 15)));
        Assertions.assertThat(patch.originalEstimateMinutes()).isEqualTo(NullableField.of(120));
        Assertions.assertThat(patch.remainingEstimateMinutes()).isEqualTo(NullableField.of(0));
    }

    @Test
    @DisplayName("is_null=true: nullable-поля передаются в сервис как «очистить»")
    void patchIssue_shouldPassNullValues_whenNullableFieldsMarkedAsNull() {
        mockPatchServiceReturns(new PatchIssueResult(issue(), false));

        NullableString nullString = NullableString.newBuilder().setIsNull(true).build();
        PatchIssueRequest request = request(body -> body
                .setDescription(nullString)
                .setAssigneeId(nullString)
                .setStoryPoints(NullableDouble.newBuilder().setIsNull(true).build())
                .setStartDate(nullString)
                .setDueDate(nullString)
                .setOriginalEstimateMinutes(NullableInt32.newBuilder().setIsNull(true).build())
                .setRemainingEstimateMinutes(NullableInt32.newBuilder().setIsNull(true).build()));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request)))
                .expectNextCount(1)
                .verifyComplete();

        IssuePatch patch = capturePatch();

        Assertions.assertThat(patch.description()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.assigneeId()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.storyPoints()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.startDate()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.dueDate()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.originalEstimateMinutes()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.remainingEstimateMinutes()).isEqualTo(NullableField.of(null));
    }

    @Test
    @DisplayName("is_null=true с непустым value: value игнорируется, поле очищается")
    void patchIssue_shouldIgnoreValue_whenIsNullTrue() {
        mockPatchServiceReturns(new PatchIssueResult(issue(), false));

        PatchIssueRequest request = request(body -> body
                .setAssigneeId(NullableString.newBuilder().setIsNull(true).setValue("not-a-uuid").build())
                .setStoryPoints(NullableDouble.newBuilder().setIsNull(true).setValue(-1).build()));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request)))
                .expectNextCount(1)
                .verifyComplete();

        IssuePatch patch = capturePatch();

        Assertions.assertThat(patch.assigneeId()).isEqualTo(NullableField.of(null));
        Assertions.assertThat(patch.storyPoints()).isEqualTo(NullableField.of(null));
    }

    @Test
    @DisplayName("Успешный patch: ответ содержит обновлённую задачу и version_conflict=false")
    void patchIssue_shouldMapResponse_whenPatchApplied() {
        Issue updated = issue().toBuilder()
                .summary("New summary")
                .version(VERSION + 1)
                .build();
        mockPatchServiceReturns(new PatchIssueResult(updated, false));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request(body -> body.setSummary("New summary")))))
                .assertNext(response -> {
                    Assertions.assertThat(response.getVersionConflict()).isFalse();
                    Assertions.assertThat(response.getIssue().getId()).isEqualTo(ISSUE_ID.toString());
                    Assertions.assertThat(response.getIssue().getSummary()).isEqualTo("New summary");
                    Assertions.assertThat(response.getIssue().getVersion()).isEqualTo(VERSION + 1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Конфликт версий: ответ содержит текущую задачу и version_conflict=true")
    void patchIssue_shouldMapResponse_whenVersionConflict() {
        Issue current = issue().toBuilder()
                .version(VERSION + 5)
                .build();
        mockPatchServiceReturns(new PatchIssueResult(current, true));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request(body -> body.setSummary("New summary")))))
                .assertNext(response -> {
                    Assertions.assertThat(response.getVersionConflict()).isTrue();
                    Assertions.assertThat(response.getIssue().getSummary()).isEqualTo("Old summary");
                    Assertions.assertThat(response.getIssue().getVersion()).isEqualTo(VERSION + 5);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("DomainException из сервиса пробрасывается без преобразования")
    void patchIssue_shouldPropagateDomainException_whenServiceFails() {
        Mockito.when(issuePatchService.patchIssue(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                        Mockito.eq(ACTOR_USER_ID), Mockito.eq(VERSION), Mockito.any(IssuePatch.class)))
                .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found")));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request(UnaryOperator.identity()))))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infrastructureErrors")
    @DisplayName("Инфраструктурные ошибки из сервиса пробрасываются без преобразования")
    void patchIssue_shouldPropagateInfrastructureError_whenServiceFails(String name, Throwable serviceError) {
        Mockito.when(issuePatchService.patchIssue(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                        Mockito.eq(ACTOR_USER_ID), Mockito.eq(VERSION), Mockito.any(IssuePatch.class)))
                .thenReturn(Mono.error(serviceError));

        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request(UnaryOperator.identity()))))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(serviceError))
                .verify();
    }

    private static Stream<Arguments> infrastructureErrors() {
        return Stream.of(
                Arguments.of("R2dbcBadGrammarException", new R2dbcBadGrammarException("Table not found")),
                Arguments.of("TransactionException", new CannotCreateTransactionException("Transaction failed")),
                Arguments.of("RuntimeException", new RuntimeException("Unexpected error"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("Невалидный запрос: INVALID_ARGUMENT, сервис не вызывается")
    void patchIssue_shouldReturnInvalidArgument_whenRequestInvalid(String fieldName, PatchIssueRequest request) {
        StepVerifier.create(grpcIssueService.patchIssue(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(StatusRuntimeException.class);

                    Status status = ((StatusRuntimeException) error).getStatus();

                    Assertions.assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    Assertions.assertThat(status.getDescription()).contains(fieldName);
                })
                .verify();

        Mockito.verifyNoInteractions(issuePatchService);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("header.requestId", requestWithHeader("", NODE_ID)),
                Arguments.of("header.nodeId", requestWithHeader(REQUEST_ID, " ")),
                Arguments.of("body.issueId", request(body -> body.setIssueId("not-a-uuid"))),
                Arguments.of("body.actorUserId", request(body -> body.setActorUserId(""))),
                Arguments.of("body.version", request(body -> body.setVersion(0))),
                Arguments.of("body.version", request(body -> body.setVersion(-1))),
                Arguments.of("body.summary", request(body -> body.setSummary(" "))),
                Arguments.of("body.priority", request(body -> body
                        .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_UNSPECIFIED))),
                Arguments.of("body.assigneeId", request(body -> body.setAssigneeId(stringValue("not-a-uuid")))),
                Arguments.of("body.storyPoints", request(body -> body
                        .setStoryPoints(NullableDouble.newBuilder().setValue(-0.5).build()))),
                Arguments.of("body.storyPoints", request(body -> body
                        .setStoryPoints(NullableDouble.newBuilder().setValue(Double.NaN).build()))),
                Arguments.of("body.startDate", request(body -> body.setStartDate(stringValue("01.10.2026")))),
                Arguments.of("body.dueDate", request(body -> body.setDueDate(stringValue("")))),
                Arguments.of("body.originalEstimateMinutes", request(body -> body
                        .setOriginalEstimateMinutes(NullableInt32.newBuilder().setValue(-1).build()))),
                Arguments.of("body.remainingEstimateMinutes", request(body -> body
                        .setRemainingEstimateMinutes(NullableInt32.newBuilder().setValue(-1).build())))
        );
    }

    // ==================== helpers ====================

    private void mockPatchServiceReturns(PatchIssueResult result) {
        Mockito.when(issuePatchService.patchIssue(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                        Mockito.eq(ACTOR_USER_ID), Mockito.eq(VERSION), Mockito.any(IssuePatch.class)))
                .thenReturn(Mono.just(result));
    }

    private IssuePatch capturePatch() {
        ArgumentCaptor<IssuePatch> captor = ArgumentCaptor.forClass(IssuePatch.class);
        Mockito.verify(issuePatchService).patchIssue(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(VERSION), captor.capture());
        return captor.getValue();
    }

    /**
     * Валидный запрос без patch-полей; {@code customizer} дополняет или портит тело.
     */
    private static PatchIssueRequest request(UnaryOperator<PatchIssueRequestBody.Builder> customizer) {
        return PatchIssueRequest.newBuilder()
                .setHeader(header(REQUEST_ID, NODE_ID))
                .setBody(customizer.apply(validBody()).build())
                .build();
    }

    private static PatchIssueRequest requestWithHeader(String requestId, String nodeId) {
        return PatchIssueRequest.newBuilder()
                .setHeader(header(requestId, nodeId))
                .setBody(validBody().build())
                .build();
    }

    private static PatchIssueRequestBody.Builder validBody() {
        return PatchIssueRequestBody.newBuilder()
                .setIssueId(ISSUE_ID.toString())
                .setActorUserId(ACTOR_USER_ID.toString())
                .setVersion(VERSION);
    }

    private static Header header(String requestId, String nodeId) {
        return Header.newBuilder()
                .setRequestId(requestId)
                .setNodeId(nodeId)
                .build();
    }

    private static NullableString stringValue(String value) {
        return NullableString.newBuilder().setValue(value).build();
    }

    private static Issue issue() {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("TAS-1")
                .issueType(IssueType.TASK)
                .summary("Old summary")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(ACTOR_USER_ID)
                .createdAt(now)
                .updatedAt(now)
                .version(VERSION)
                .build();
    }
}
