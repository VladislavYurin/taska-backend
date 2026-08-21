package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.AddIssueLabelRequest;
import ru.taska.api.issue.v1.AddIssueLabelRequestBody;
import ru.taska.api.issue.v1.CreateProjectLabelRequest;
import ru.taska.api.issue.v1.CreateProjectLabelRequestBody;
import ru.taska.api.issue.v1.DeleteProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelRequestBody;
import ru.taska.api.issue.v1.ListIssueLabelsRequest;
import ru.taska.api.issue.v1.ListIssueLabelsRequestBody;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsRequestBody;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.RemoveIssueLabelRequest;
import ru.taska.api.issue.v1.RemoveIssueLabelRequestBody;
import ru.taska.api.issue.v1.UpdateProjectLabelRequest;
import ru.taska.api.issue.v1.UpdateProjectLabelRequestBody;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AddIssueLabelRequestDto;
import ru.taska.domain.dto.AddIssueLabelResponseDto;
import ru.taska.domain.dto.CreateProjectLabelRequestDto;
import ru.taska.domain.dto.ListIssueLabelsResponseDto;
import ru.taska.domain.dto.ListProjectLabelsResponseDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;

import ru.taska.domain.dto.UpdateProjectLabelRequestDto;
import ru.taska.mapper.LabelMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с метками issue-service.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcLabelServiceClient {

    private final ReactorIssueServiceGrpc.ReactorIssueServiceStub issueServiceStub;
    private final LabelMapper labelMapper;
    private final GrpcClientProperties properties;

    /**
     * Создает новую метку на проекте
     * @param projectId айди проекта
     * @param request dto запроса
     * @param context контекст запроса
     * @return созданную метку
     */
    public Mono<ProjectLabelResponseDto> createProjectLabel(
            String projectId,
            Mono<CreateProjectLabelRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling createProjectLabel", context.requestId());

        return request
                .flatMap(requestDto ->
                        dynamicStub().createProjectLabel(
                                CreateProjectLabelRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                CreateProjectLabelRequestBody.newBuilder()
                                                        .setProjectId(projectId)
                                                        .setName(requestDto.getName())
                                                        .setColor(requestDto.getColor())
                                                        .setCreatedBy(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )

                )
                .map(labelMapper::toRestProjectLabelResponse);
    }

    /**
     * Обновляет метку на проекте
     * @param projectId айди проекта
     * @param labelId айди метки
     * @param request dto запроса
     * @param context контекст запроса
     * @return обновленную метку
     */
    public Mono<ProjectLabelResponseDto> updateProjectLabel(
            String projectId,
            String labelId,
            Mono<UpdateProjectLabelRequestDto> request,
            GatewayContext context
    ) {
        log.debug("[{}] Calling updateProjectLabel", context.requestId());

        return request
                .flatMap(requestDto ->
                        dynamicStub().updateProjectLabel(
                                UpdateProjectLabelRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                UpdateProjectLabelRequestBody.newBuilder()
                                                        .setProjectId(projectId)
                                                        .setLabelId(labelId)
                                                        .setName(requestDto.getName())
                                                        .setColor(requestDto.getColor())
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )

                )
                .map(labelMapper::toRestProjectLabelResponse);
    }

    /**
     * Удаляет метку (мягко) с проекта
     * @param projectId айди проекта
     * @param labelId айди метки
     * @param context контекст запроса
     * @return void
     */
    public Mono<Void> deleteProjectLabel(
            String projectId,
            String labelId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteProjectLabel", context.requestId());

        return dynamicStub().deleteProjectLabel(
                DeleteProjectLabelRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                DeleteProjectLabelRequestBody.newBuilder()
                                        .setProjectId(projectId)
                                        .setLabelId(labelId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        )
        .then();
    }

    /**
     * Получает список меток проекта
     * @param projectId айди проекта
     * @param context контекст запроса
     * @return список меток проекта
     */
    public Mono<ListProjectLabelsResponseDto> listProjectLabels(
            String projectId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling listProjectLabels", context.requestId());

        return dynamicStub().listProjectLabels(
                ListProjectLabelsRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                ListProjectLabelsRequestBody.newBuilder()
                                        .setProjectId(projectId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        )
        .map(labelMapper::toRestListProjectLabelsResponse);
    }

    /**
     * Добавляет существующую на проекте метку к задаче
     * @param issueId айди задачи
     * @param request параметры запроса
     * @param context контекст запроса
     * @return добавленную к задаче метку
     */
    public Mono<AddIssueLabelResponseDto> addIssueLabel(
            String issueId,
            Mono<AddIssueLabelRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling addIssueLabel", context.requestId());

        return request
                .flatMap(requestDto ->
                        dynamicStub().addIssueLabel(
                                AddIssueLabelRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(
                                                AddIssueLabelRequestBody.newBuilder()
                                                        .setIssueId(issueId)
                                                        .setLabelId(requestDto.getLabelId().toString())
                                                        .setActorUserId(context.userContext().userId())
                                                        .build()
                                        )
                                        .build()
                        )
                )
                .map(labelMapper::toRestAddIssueLabelResponse);
    }

    /**
     * Убирает метку с задачи
     * @param issueId айди задачи
     * @param labelId айди метки
     * @param context контекст запроса
     * @return void
     */
    public Mono<Void> removeIssueLabel(
            String issueId,
            String labelId,
            GatewayContext context
    ) {
        log.info("[{}] Calling removeIssueLabel", context.requestId());

        return dynamicStub().removeIssueLabel(
                RemoveIssueLabelRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                RemoveIssueLabelRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setLabelId(labelId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).then();
    }

    /**
     * Получает список меток задачи
     * @param issueId айди задачи
     * @param context контекст запроса
     * @return список меток
     */
    public Mono<ListIssueLabelsResponseDto> listIssueLabels(
            String issueId,
            GatewayContext context
    ) {
        log.debug("[{}] Calling listIssueLabels", context.requestId());

        return dynamicStub().listIssueLabels(
                ListIssueLabelsRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                ListIssueLabelsRequestBody.newBuilder()
                                        .setIssueId(issueId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
                )
                .map(labelMapper::toRestListIssueLabelsResponse);
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorIssueServiceGrpc.ReactorIssueServiceStub dynamicStub() {
        return issueServiceStub.withDeadlineAfter(
                properties.issueService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
