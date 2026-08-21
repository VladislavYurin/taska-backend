package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.LabelsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AddIssueLabelRequestDto;
import ru.taska.domain.dto.AddIssueLabelResponseDto;
import ru.taska.domain.dto.CreateProjectLabelRequestDto;
import ru.taska.domain.dto.ListIssueLabelsResponseDto;
import ru.taska.domain.dto.ListProjectLabelsResponseDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;
import ru.taska.domain.dto.UpdateProjectLabelRequestDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcLabelServiceClient;

import java.util.UUID;


/**
 * REST-контроллер для работы с метками задач и метками на проектах.
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с issue-сервисом через {@link GrpcIssueServiceClient}.
 */
@RestController
@RequiredArgsConstructor
public class LabelsController implements LabelsApi {

    private final GatewayRequestExecutor executor;
    private final GrpcLabelServiceClient grpcClient;

    /**
     * Создание метки на проекте
     */
    @Override
    public Mono<ResponseEntity<ProjectLabelResponseDto>> createProjectLabel(
            UUID projectId,
            Mono<CreateProjectLabelRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.createProjectLabel(projectId.toString(), requestDto, context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        );
    }

    /**
     * Обновление метки на проекте
     */
    @Override
    public Mono<ResponseEntity<ProjectLabelResponseDto>> updateProjectLabel(
            UUID projectId,
            UUID labelId,
            Mono<UpdateProjectLabelRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.updateProjectLabel(projectId.toString(), labelId.toString(), requestDto, context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Удаление метки с проекта
     */
    @Override
    public Mono<ResponseEntity<Void>> deleteProjectLabel(
            UUID projectId,
            UUID labelId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.deleteProjectLabel(projectId.toString(), labelId.toString(), context)
                        .thenReturn(ResponseEntity.noContent().build())
        );
    }

    /**
     * Получение списка меток на проекте
     */
    @Override
    public Mono<ResponseEntity<ListProjectLabelsResponseDto>> listProjectLabels(
            UUID projectId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.listProjectLabels(projectId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Добавление метки на задачу
     */
    @Override
    public Mono<ResponseEntity<AddIssueLabelResponseDto>> addIssueLabel(
            UUID projectId,
            UUID issueId,
            Mono<AddIssueLabelRequestDto> requestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.addIssueLabel(issueId.toString(), requestDto, context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        );
    }

    /**
     * Открепление метки с задачи
     */
    @Override
    public Mono<ResponseEntity<Void>> removeIssueLabel(
            UUID projectId,
            UUID issueId,
            UUID labelId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.removeIssueLabel(issueId.toString(), labelId.toString(), context)
                        .thenReturn(ResponseEntity.noContent().build())
        );
    }

    /**
     * Получение списка меток задачи
     */
    @Override
    public Mono<ResponseEntity<ListIssueLabelsResponseDto>> listIssueLabels(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcClient.listIssueLabels(issueId.toString(), context)
                        .map(ResponseEntity::ok)
        );
    }
}
