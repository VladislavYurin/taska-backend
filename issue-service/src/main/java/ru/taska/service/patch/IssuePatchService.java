package ru.taska.service.patch;

import reactor.core.publisher.Mono;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.PatchIssueResult;

import java.util.UUID;

/**
 * Сервис для частичного обновления задачи (PATCH).
 */
public interface IssuePatchService {

    /**
     * Частично обновляет задачу (PATCH) с оптимистичной блокировкой по версии.
     *
     * @param requestId      ID запроса
     * @param nodeId         ID узла
     * @param issueId        ID изменяемой задачи
     * @param actorUserId    ID пользователя, инициировавшего запрос
     * @param ifMatchVersion версия задачи из заголовка If-Match (оптимистичная блокировка)
     * @param patch          изменяемые поля задачи
     * @return Mono<{@link PatchIssueResult}> с актуальным состоянием задачи и признаком конфликта версий.
     * {@code versionConflict == true} — версия из {@code If-Match} не совпала с текущей версией задачи в БД,
     * изменения не применены, {@code issue} содержит текущее (неизменённое) состояние задачи.
     * {@code versionConflict == false} — изменения применены, {@code issue} содержит уже обновлённое состояние задачи.
     */
    Mono<PatchIssueResult> patchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            int ifMatchVersion,
            IssuePatch patch
    );
}
