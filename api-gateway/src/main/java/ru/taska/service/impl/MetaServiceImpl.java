package ru.taska.service.impl;

import lombok.Getter;
import org.springframework.stereotype.Service;
import ru.taska.domain.dto.*;
import ru.taska.service.MetaService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Сервис словарей допустимых значений перечислений для {@code GET /api/v1/meta}.
 * <p>
 * Коды берутся из OpenAPI DTO-перечислений — тех же, через которые другие маршруты
 * принимают и отдают значения, поэтому словарь не может разойтись с контрактом.
 * Названия заданы исчерпывающими {@code switch}: новое значение в контракте без
 * названия не скомпилируется.
 * <p>
 * Словари неизменны в пределах версии gateway, поэтому ответ и его ETag
 * вычисляются один раз при старте.
 */
@Service
@Getter
public class MetaServiceImpl implements MetaService {

    static final int ETAG_BYTES = 16;

    private final MetaResponseDto meta;
    private final String etag;

    public MetaServiceImpl() {
        this.meta = new MetaResponseDto(
                toPairList(IssueTypeDto.values(), code -> new IssueTypeMetaPairDto(code, issueTypeName(code))),
                toPairList(IssuePriorityDto.values(), code -> new IssuePriorityMetaPairDto(code, priorityName(code))),
                toPairList(IssueLinkTypeDto.values(), code -> new IssueLinkTypeMetaPairDto(code, linkTypeName(code))),
                toPairList(NotificationTypeDto.values(), code -> new NotificationTypeMetaPairDto(code, notificationTypeName(code))),
                toPairList(UserStatusDto.values(), code -> new UserStatusMetaPairDto(code, userStatusName(code)))
        );
        this.etag = computeEtag(meta);
    }

    private static String issueTypeName(IssueTypeDto code) {
        return switch (code) {
            case TASK -> "Задача";
            case BUG -> "Ошибка";
            case STORY -> "История";
        };
    }

    private static String priorityName(IssuePriorityDto code) {
        return switch (code) {
            case LOW -> "Низкий";
            case MEDIUM -> "Средний";
            case HIGH -> "Высокий";
        };
    }

    private static String linkTypeName(IssueLinkTypeDto code) {
        return switch (code) {
            case BLOCKS -> "Блокирует";
            case RELATES_TO -> "Связана с";
            case DUPLICATES -> "Дублирует";
        };
    }

    private static String notificationTypeName(NotificationTypeDto code) {
        return switch (code) {
            case ISSUE_ASSIGNED -> "Назначена задача";
            case ISSUE_TRANSITIONED -> "Сменился статус";
            case ISSUE_CREATED -> "Создана задача";
            case ISSUE_UPDATED -> "Изменена задача";
            case ISSUE_DELETED -> "Удалена задача";
            case USER_INVITED -> "Приглашён пользователь";
            case USER_ACTIVATED -> "Активирован пользователь";
            case PROJECT_CREATED -> "Создан проект";
            case MEMBER_ADDED -> "Добавлен участник";
            case MEMBER_UPDATED -> "Изменена роль участника";
            case MEMBER_REMOVED -> "Удалён участник";
            case ISSUE_LINK_CREATED -> "Создана связь задач";
            case ISSUE_LINK_DELETED -> "Удалена связь задач";
            case LABEL_ADDED -> "Добавлена метка";
            case LABEL_REMOVED -> "Удалена метка";
            case USER_BLOCKED -> "Заблокирован пользователь";
            case USER_UNBLOCKED -> "Разблокирован пользователь";
            case ISSUE_COMMENT_CREATED -> "Добавлен комментарий";
            case ISSUE_COMMENT_UPDATED -> "Изменён комментарий";
            case ISSUE_COMMENT_DELETED -> "Удалён комментарий";
            case ISSUE_ATTACHMENT_ADDED -> "Добавлено вложение";
            case ISSUE_ATTACHMENT_DELETED -> "Удалено вложение";
            case UNKNOWN -> "Неизвестный тип";
        };
    }

    private static String userStatusName(UserStatusDto code) {
        return switch (code) {
            case UNSPECIFIED -> "Не указан";
            case INVITED -> "Приглашён";
            case ACTIVE -> "Активен";
            case BLOCKED -> "Заблокирован";
            case LOCKED -> "Залочен";
        };
    }

    private static <Enum, Pair> List<Pair> toPairList(Enum[] codes, Function<Enum, Pair> toPair) {
        return Arrays.stream(codes).map(toPair).toList();
    }

    /**
     * ETag — префикс SHA-256 от {@code toString()} ответа: сгенерированные DTO
     * перечисляют в нём все поля, так что любое изменение кода или названия меняет ETag.
     */
    private static String computeEtag(MetaResponseDto meta) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(meta.toString().getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, ETAG_BYTES) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
