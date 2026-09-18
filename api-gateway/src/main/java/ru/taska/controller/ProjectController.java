package ru.taska.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import ru.taska.api.ProjectApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AddProjectMemberRequestDto;
import ru.taska.domain.dto.ChangeProjectMemberRoleRequestDto;
import ru.taska.domain.dto.CreateProjectRequestDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.domain.dto.UpdateProjectRequestDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcProjectServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * REST-контроллер для работы с проектами
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с project-сервисом через {@link GrpcProjectServiceClient}.
 */
@RestController
@RequiredArgsConstructor
public class ProjectController implements ProjectApi {

    private final GatewayRequestExecutor executor;
    private final GrpcProjectServiceClient projectClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * POST /api/v1/projects
     * Создает проект → 201 CREATED
     */
    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> createProject(
            Mono<CreateProjectRequestDto> createProjectRequestDto,
            ServerWebExchange exchange
    ) {
        return createProjectRequestDto.flatMap(request -> {
                    return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                        projectClient.createProject(Mono.just(request), context))
                            .map(responseBody -> ResponseEntity.status(HttpStatus.CREATED).body(responseBody));

       });
    }

    /**
     * GET /api/v1/projects/{projectId}
     * Получает проект по ID → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> getProject(
            String projectId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                projectClient.getProject(projectId,context))
                        .map(ResponseEntity::ok);
    }

    /**
     * PATCH /api/v1/projects/{projectId}
     * Обновляет проект (имя/описание/цвет, PATCH-семантика) → 200 OK
     * <p>
     * Тело запроса читается вручную (через {@link JsonNode}), а не через стандартный
     * {@code @RequestBody}-декодер, потому что Jackson-десериализация в POJO не различает
     * "поле отсутствует в JSON" и "поле явно передано как null" — оба дают null в DTO.
     * Явный null у color/description трактуется как сброс значения в null; отсутствие поля —
     * как "не менять" (см. {@link ru.taska.domain.dto.UpdateProjectRequestDto}). Явный null у name,
     * в отличие от color/description, недопустим и приводит к 400 — "имя = null" бизнес-логически
     * бессмысленно, как и пустая строка (которая уже отклоняется через {@code @Size(min = 1)}).
     * Bean Validation не видит разницы между "null" и "не передано" на уровне DTO-поля, поэтому
     * эта проверка сделана вручную, до вызова {@link #validate}.
     * Параметр {@code updateProjectRequestDto}, инжектируемый сгенерированным {@link ProjectApi},
     * намеренно не используется — тело запроса имеет ровно одного подписчика (ниже), чтобы не
     * читать реактивный body-поток дважды.
     */
    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> updateProject(
            String projectId,
            Mono<UpdateProjectRequestDto> updateProjectRequestDto,
            ServerWebExchange exchange
    ) {
        return readBodyAsJsonNode(exchange).flatMap(bodyNode -> {
            if (isExplicitNull(bodyNode, "name")) {
                return Mono.error(new ServerWebInputException("Field 'name' must not be null"));
            }

            boolean clearColor = isExplicitNull(bodyNode, "color");
            boolean clearDescription = isExplicitNull(bodyNode, "description");

            UpdateProjectRequestDto dto = objectMapper.treeToValue(bodyNode, UpdateProjectRequestDto.class);
            validate(dto);

            return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                    projectClient.updateProject(projectId, Mono.just(dto), clearColor, clearDescription, context))
                            .map(ResponseEntity::ok);
        });
    }

    private Mono<JsonNode> readBodyAsJsonNode(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(this::parseJsonNode)
                .switchIfEmpty(Mono.error(new ServerWebInputException("Request body is required")));
    }

    private JsonNode parseJsonNode(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return objectMapper.readTree(bytes);
        } catch (Exception e) {
            throw new ServerWebInputException("Malformed JSON request body", null, e);
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private boolean isExplicitNull(JsonNode bodyNode, String fieldName) {
        return bodyNode.has(fieldName) && bodyNode.get(fieldName).isNull();
    }

    private void validate(UpdateProjectRequestDto dto) {
        Set<ConstraintViolation<UpdateProjectRequestDto>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /**
     * GET /api/v1/projects
     * Получает проекты текущего пользователя → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ListMyProjectResponseDto>> listMyProjects(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, projectClient::listMyProjects)
                        .map(ResponseEntity::ok);
    }

    /**
     * POST /api/v1/projects/{projectId}/members
     * Добавляет участника → 201 CREATED
     */
    @Override
    public Mono<ResponseEntity<ProjectMemberResponseDto>> addProjectMember(
            String projectId,
            Mono<AddProjectMemberRequestDto> addProjectMemberRequestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                projectClient.addProjectMember(projectId,addProjectMemberRequestDto,context))
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * PATCH /api/v1/projects/{projectId}/members/{userId}
     * Меняет роль участника → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ProjectMemberResponseDto>> changeProjectMemberRole(
            String projectId,
            String userId,
            Mono<ChangeProjectMemberRoleRequestDto> changeProjectMemberRoleRequestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                projectClient.changeProjectMemberRole(projectId,userId,changeProjectMemberRoleRequestDto,context))
                        .map(ResponseEntity::ok);
    }

    /**
     * DELETE /api/v1/projects/{projectId}/members/{userId}
     * Удаляет участника → 204 NO CONTENT
     */
    @Override
    public Mono<ResponseEntity<Void>> removeProjectMember(
            String projectId,
            String userId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                projectClient.removeProjectMember(projectId,userId,context))
                        .thenReturn(ResponseEntity.noContent().build());
    }
}
