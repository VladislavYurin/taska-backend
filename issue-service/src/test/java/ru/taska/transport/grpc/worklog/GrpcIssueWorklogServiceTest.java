package ru.taska.transport.grpc.worklog;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.AddIssueWorklogBody;
import ru.taska.api.issue.v1.AddIssueWorklogRequest;
import ru.taska.api.issue.v1.DeleteIssueWorklogBody;
import ru.taska.api.issue.v1.DeleteIssueWorklogRequest;
import ru.taska.api.issue.v1.ListIssueWorklogsBody;
import ru.taska.api.issue.v1.ListIssueWorklogsRequest;
import ru.taska.api.issue.v1.UpdateIssueWorklogBody;
import ru.taska.api.issue.v1.UpdateIssueWorklogRequest;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.mapper.WorklogMapper;
import ru.taska.service.worklog.WorklogService;
import ru.taska.transport.grpc.GrpcIssueWorklogService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Unit-тесты gRPC-транспортного слоя worklog-эндпоинтов.
 *
 * <p>{@link WorklogMapper} используется как настоящий объект (он без зависимостей, см.
 * {@code WorklogMapperTest}), {@link WorklogService} мокается — проверяем только
 * валидацию/маппинг запроса и ответа, не бизнес-логику (она покрыта
 * {@code WorklogServiceImplTest}/{@code WorklogExecutorTest}/{@code WorklogIT}).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcIssueWorklogService Unit Tests")
class GrpcIssueWorklogServiceTest {

    @Mock
    private WorklogService worklogService;

    private final WorklogMapper worklogMapper = new WorklogMapper();

    private GrpcIssueWorklogService grpcIssueWorklogService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKLOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    @BeforeEach
    void setUp() {
        grpcIssueWorklogService = new GrpcIssueWorklogService(worklogService, worklogMapper);
    }

    private Header validHeader() {
        return Header.newBuilder().setRequestId(REQUEST_ID).setNodeId(NODE_ID).build();
    }

    private Worklog sampleWorklog() {
        return Worklog.builder()
                .id(WORKLOG_ID)
                .issueId(ISSUE_ID)
                .projectId(PROJECT_ID)
                .authorUserId(ACTOR_ID)
                .spentMinutes(30)
                .workDate(LocalDate.now())
                .version(1)
                .build();
    }

    private void assertInvalidArgument(Throwable error, String expectedFieldFragment) {
        Assertions.assertThat(error).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException sre = (StatusRuntimeException) error;
        Assertions.assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        Assertions.assertThat(sre.getStatus().getDescription()).contains(expectedFieldFragment);
    }

    // ===== addIssueWorklog =====

    @Test
    @DisplayName("addIssueWorklog: должен смаппить валидный запрос и вернуть ответ")
    void addIssueWorklog_shouldMapValidRequestAndReturnResponse() {
        AddIssueWorklogRequest request = AddIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(AddIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(30)
                        .setWorkDate(LocalDate.now().toString())
                        .setComment("worked")
                        .build())
                .build();

        Mockito.when(worklogService.addIssueWorklog(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.eq(ISSUE_ID), Mockito.eq(ACTOR_ID), Mockito.any(CreateWorklogDto.class)))
                .thenReturn(Mono.just(sampleWorklog()));

        StepVerifier.create(grpcIssueWorklogService.addIssueWorklog(Mono.just(request)))
                .assertNext(response -> Assertions.assertThat(response.getWorklog().getId())
                        .isEqualTo(WORKLOG_ID.toString()))
                .verifyComplete();
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить пустой requestId без обращения к сервису")
    void addIssueWorklog_shouldRejectBlankRequestId() {
        AddIssueWorklogRequest request = AddIssueWorklogRequest.newBuilder()
                .setHeader(Header.newBuilder().setNodeId(NODE_ID).build())
                .setBody(AddIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(30)
                        .setWorkDate(LocalDate.now().toString())
                        .build())
                .build();

        StepVerifier.create(grpcIssueWorklogService.addIssueWorklog(Mono.just(request)))
                .expectErrorSatisfies(error -> assertInvalidArgument(error, "requestId"))
                .verify();

        Mockito.verifyNoInteractions(worklogService);
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить невалидный UUID issueId")
    void addIssueWorklog_shouldRejectInvalidIssueIdUuid() {
        AddIssueWorklogRequest request = AddIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(AddIssueWorklogBody.newBuilder()
                        .setIssueId("not-a-uuid")
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(30)
                        .setWorkDate(LocalDate.now().toString())
                        .build())
                .build();

        StepVerifier.create(grpcIssueWorklogService.addIssueWorklog(Mono.just(request)))
                .expectErrorSatisfies(error -> assertInvalidArgument(error, "issueId"))
                .verify();

        Mockito.verifyNoInteractions(worklogService);
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить неположительный spentMinutes")
    void addIssueWorklog_shouldRejectNonPositiveSpentMinutes() {
        AddIssueWorklogRequest request = AddIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(AddIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(0)
                        .setWorkDate(LocalDate.now().toString())
                        .build())
                .build();

        StepVerifier.create(grpcIssueWorklogService.addIssueWorklog(Mono.just(request)))
                .expectErrorSatisfies(error -> assertInvalidArgument(error, "spentMinutes"))
                .verify();

        Mockito.verifyNoInteractions(worklogService);
    }

    @Test
    @DisplayName("addIssueWorklog: должен отклонить невалидный формат workDate")
    void addIssueWorklog_shouldRejectInvalidWorkDateFormat() {
        AddIssueWorklogRequest request = AddIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(AddIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(30)
                        .setWorkDate("not-a-date")
                        .build())
                .build();

        StepVerifier.create(grpcIssueWorklogService.addIssueWorklog(Mono.just(request)))
                .expectErrorSatisfies(error -> assertInvalidArgument(error, "workDate"))
                .verify();

        Mockito.verifyNoInteractions(worklogService);
    }

    // ===== updateIssueWorklog =====

    @Test
    @DisplayName("updateIssueWorklog: должен смаппить отсутствующие опциональные поля как null")
    void updateIssueWorklog_shouldMapOptionalFieldsAbsent() {
        UpdateIssueWorklogRequest request = UpdateIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(UpdateIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setWorklogId(WORKLOG_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .build())
                .build();

        ArgumentCaptor<UpdateWorklogDto> dtoCaptor = ArgumentCaptor.forClass(UpdateWorklogDto.class);
        Mockito.when(worklogService.updateIssueWorklog(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.eq(ISSUE_ID), Mockito.eq(WORKLOG_ID), Mockito.eq(ACTOR_ID), dtoCaptor.capture()))
                .thenReturn(Mono.just(sampleWorklog()));

        StepVerifier.create(grpcIssueWorklogService.updateIssueWorklog(Mono.just(request)))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(dtoCaptor.getValue().spentMinutes()).isNull();
        Assertions.assertThat(dtoCaptor.getValue().workDate()).isNull();
        Assertions.assertThat(dtoCaptor.getValue().comment()).isNull();
    }

    @Test
    @DisplayName("updateIssueWorklog: должен отклонить spentMinutes=0, если поле явно передано")
    void updateIssueWorklog_shouldRejectZeroSpentMinutesWhenPresent() {
        UpdateIssueWorklogRequest request = UpdateIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(UpdateIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setWorklogId(WORKLOG_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setSpentMinutes(0)
                        .build())
                .build();

        StepVerifier.create(grpcIssueWorklogService.updateIssueWorklog(Mono.just(request)))
                .expectErrorSatisfies(error -> assertInvalidArgument(error, "spentMinutes"))
                .verify();

        Mockito.verifyNoInteractions(worklogService);
    }

    // ===== deleteIssueWorklog =====

    @Test
    @DisplayName("deleteIssueWorklog: должен смаппить валидный запрос")
    void deleteIssueWorklog_shouldMapValidRequest() {
        DeleteIssueWorklogRequest request = DeleteIssueWorklogRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(DeleteIssueWorklogBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setWorklogId(WORKLOG_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .build())
                .build();

        Mockito.when(worklogService.deleteIssueWorklog(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.eq(ISSUE_ID), Mockito.eq(WORKLOG_ID), Mockito.eq(ACTOR_ID)))
                .thenReturn(Mono.just(sampleWorklog()));

        StepVerifier.create(grpcIssueWorklogService.deleteIssueWorklog(Mono.just(request)))
                .assertNext(response -> Assertions.assertThat(response.getWorklog().getId())
                        .isEqualTo(WORKLOG_ID.toString()))
                .verifyComplete();
    }

    // ===== listIssueWorklogs =====

    @Test
    @DisplayName("listIssueWorklogs: должен смаппить валидный запрос")
    void listIssueWorklogs_shouldMapValidRequest() {
        ListIssueWorklogsRequest request = ListIssueWorklogsRequest.newBuilder()
                .setHeader(validHeader())
                .setBody(ListIssueWorklogsBody.newBuilder()
                        .setIssueId(ISSUE_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .build())
                .build();

        Mockito.when(worklogService.listIssueWorklog(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.eq(ISSUE_ID), Mockito.eq(ACTOR_ID)))
                .thenReturn(Mono.just(List.of(sampleWorklog())));

        StepVerifier.create(grpcIssueWorklogService.listIssueWorklogs(Mono.just(request)))
                .assertNext(response -> Assertions.assertThat(response.getWorklogsList()).hasSize(1))
                .verifyComplete();
    }
}