package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.IssueCommentResponse;
import ru.taska.api.issue.v1.ListIssueCommentsResponse;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.domain.dto.CommentResponseDto;
import ru.taska.domain.dto.CommentsListResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Компонент-маппер для преобразования моделей комментариев между gRPC и REST слоями API Gateway.
 * <p>
 * Преобразует Protobuf сообщения issue-service в REST DTO, которые возвращаются frontend-клиенту.
 */
@Component
@RequiredArgsConstructor
public class CommentRestMapper {

    /**
     * Преобразует gRPC ответ с комментарием в REST DTO.
     *
     * @param proto gRPC объект {@link IssueCommentResponse}, полученный от issue-service
     * @return заполненный REST DTO {@link CommentResponseDto} для ответа frontend-клиенту
     */
    public CommentResponseDto toCommentResponseDto(IssueCommentResponse proto) {
        CommentResponseDto dto = new CommentResponseDto();

        dto.setId(toUuid(proto.getId()));
        dto.setIssueId(toUuid(proto.getIssueId()));
        dto.setProjectId(toUuid(proto.getProjectId()));
        dto.setAuthorUserId(toUuid(proto.getAuthorUserId()));
        dto.setBody(proto.getBody());
        dto.setCreatedAt(toOffsetDateTime(proto.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(proto.getUpdatedAt()));
        dto.setVersion(proto.getVersion());

        return dto;
    }

    /**
     * Преобразует gRPC ответ добавления комментария в REST DTO.
     *
     * @param response gRPC объект {@link AddIssueCommentResponse}
     * @return заполненный REST DTO {@link CommentResponseDto}
     */
    public CommentResponseDto toCommentResponseDto(AddIssueCommentResponse response) {
        return toCommentResponseDto(response.getComment());
    }

    /**
     * Преобразует gRPC ответ обновления комментария в REST DTO.
     *
     * @param response gRPC объект {@link UpdateIssueCommentResponse}
     * @return заполненный REST DTO {@link CommentResponseDto}
     */
    public CommentResponseDto toCommentResponseDto(UpdateIssueCommentResponse response) {
        return toCommentResponseDto(response.getComment());
    }

    /**
     * Преобразует gRPC ответ удаления комментария в REST DTO.
     *
     * @param response gRPC объект {@link DeleteIssueCommentResponse}
     * @return заполненный REST DTO {@link CommentResponseDto} с идентификатором удалённого комментария
     */
    public CommentResponseDto toDeletedCommentResponseDto(DeleteIssueCommentResponse response) {
        CommentResponseDto dto = new CommentResponseDto();
        dto.setId(toUuid(response.getCommentId()));
        return dto;
    }

    /**
     * Преобразует gRPC ответ со списком комментариев в REST DTO ответа.
     * <p>
     * В REST API список комментариев возвращается внутри поля {@code items}.
     * Если комментариев нет, клиент получает пустой список.
     *
     * @param response ответ {@link ListIssueCommentsResponse}, полученный от issue-service
     * @return заполненный REST DTO {@link CommentsListResponseDto} со списком комментариев и общим количеством
     */
    public CommentsListResponseDto toCommentsListResponseDto(ListIssueCommentsResponse response) {
        CommentsListResponseDto dto = new CommentsListResponseDto();

        dto.setItems(
                response.getCommentsList().stream()
                        .map(this::toCommentResponseDto)
                        .collect(Collectors.toList())
        );
        dto.setTotalCount(response.getTotalCount());

        return dto;
    }

    /**
     * Преобразует строку в UUID.
     *
     * @param value строковое представление UUID
     * @return {@link UUID} или null, если строка null или пустая
     * @throws IllegalArgumentException если строка не является валидным UUID
     */
    private UUID toUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    /**
     * Преобразует Protobuf timestamp в {@link OffsetDateTime} с UTC offset.
     *
     * @param timestamp временная метка из Protobuf сообщения
     * @return дата и время в формате UTC для REST ответа, или null если timestamp не задан
     */
    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}