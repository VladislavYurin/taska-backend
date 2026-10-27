# Удаление типа с переносом задач — TAS-433

`DELETE /api/v1/projects/{projectId}/issue-types/{code}` сохраняет прежний вызов без тела: непустой тип возвращает `ISSUE_TYPE_IN_USE` и точный `issueCount`, последний тип — `LAST_ISSUE_TYPE`. В обоих случаях задачи остаются на месте. Удалённые задачи в счётчик не входят.

Для переноса передать `moveIssuesTo` и, при необходимости, `statusMappings`. Цель должна быть другим зарегистрированным типом того же проекта. Если исходный статус есть в целевом workflow, он сохраняется; явное сопоставление имеет приоритет. Для остальных статусов обязательна запись в `statusMappings`. Ответ `ISSUE_STATUSES_UNMAPPED` содержит все `statusKeys` и `targetStatusKeys`; оба внутренних RPC передают большой отказ в теле сообщения, а не в ограниченных gRPC trailers. Лимит — 5000 **всех** живых задач исходного типа, включая задачи с уже допустимыми статусами и отсутствующие на доске. Превышение возвращает `WORKFLOW_MIGRATION_LIMIT` до изменений.

Реестр возвращает необязательное read-only UUID `generation`. Для нового move-вызова рекомендуется передавать его в `expectedGeneration`. После удаления и повторного создания того же кода этот параметр обязателен (`ISSUE_TYPE_GENERATION_REQUIRED`); устаревшее значение возвращает `ISSUE_TYPE_CHANGED`. Старый DELETE без переноса продолжает удалять пустой воссозданный тип без этого параметра.

## Транзакции и восстановление

Операция использует долговечный журнал и доведение вперёд между независимыми БД. Изменения всех задач, их версий, истории, outbox и receipt выполняются **одной транзакцией issue-service**. Это не распределённая транзакция трёх PostgreSQL.

1. Короткая транзакция project-service блокирует проект, проверяет ADMIN и архив, сохраняет неизменяемый intent с поколениями source/target и исходной атрибуцией. Обе строки реестра получают `pending_operation_id`. На проект допускается одна незавершённая операция.
2. Workflow-service берёт проектный advisory lock (seed 418), дожидается начатых мутаций/binding и сохраняет snapshot целевого workflow под row lock. Пока intent/pin активен, новые изменения workflow этого проекта отклоняются. Резервирование всегда предшествует issue lock, чтобы не образовать цикл с миграцией workflow.
3. Issue-service берёт проектный transaction advisory lock (seed 433). Создание задач и PATCH используют тот же порядок перед свежей проверкой реестра и блокировкой строк задач. Начатая запись завершается до переноса; новая запись в pending/retired поколение отклоняется. Проверяются текущие ADMIN/архив, поколения и workflow snapshot. Затем все живые source-задачи блокируются по `id` и переносятся с version predicate. На задачу создаётся ровно один `UPDATED` history и один `IssueUpdated` outbox со снимками исполнителя/наблюдателей, старого/нового типа и статуса. Второй `TRANSITIONED` не создаётся.
4. APPLIED receipt навсегда помечает исходное поколение как завершённое. Project-service удаляет ровно source generation с тем же intent, обновляет legacy settings, снимает target fence и помечает intent COMPLETED. Затем освобождает workflow pin и отмечает получение подтверждения.

При окончательном отказе **до** issue commit project-service сохраняет CANCELLING. Issue-service под тем же lock записывает CANCELLED receipt либо возвращает APPLIED, если конкурирующий apply уже победил. Только после CANCELLED разрешено снимать project fence. CANCELLED receipt не запрещает новые записи активного поколения. Ошибка записи истории/outbox откатывает весь issue перенос вместе с receipt.

Потеря ответа/commit failure после APPLIED не вызывает обратного переноса: повтор использует тот же receipt и доводит удаление реестра вперёд без новых history/outbox. Публичный повтор снова проверяет актуальные права. Recovery worker продолжает ранее проверенный intent даже после отзыва ADMIN/архивации проекта; actor, канал и приватный UUID токена берутся из исходного intent. В истории публично показывается только канал; UUID токена отсутствует в outbox payload.

Неопределённый исход возвращает HTTP 503 `ISSUE_TYPE_DELETION_PENDING` с `operationId` для администратора. Следует повторить тот же DELETE, а не создавать другую команду. Write-guard ошибки обычных create/PATCH не раскрывают UUID операции. Период восстановления: `app.issue-type-deletions.recovery-interval` (по умолчанию 5 секунд). Терминальные записи с неподтверждённым освобождением pin также восстанавливаются. Журналы, receipt и tombstone не имеют TTL и не удаляются автоматически. Зависимые сервисы должны снова стать доступны для завершения операции; до этого fences остаются активными.

## Выкатка и проверки

Нужна согласованная выкладка project-service, issue-service, workflow-service и gateway с новыми миграциями/RPC. Во время использования удаления не должно оставаться старых экземпляров issue/workflow writers, которые не соблюдают новые fences. Предыдущие Liquibase changesets не меняются. Откат схемы с сохранёнными intent/receipt требует отдельного плана; нельзя удалять эти записи или принудительно снимать pending/pin при неопределённом исходе.

Новые PG проверки запускаются явно (обычный Surefire исключает `*IT`):

```sh
./mvnw -pl issue-service -am test -Dtest=IssueTypeMoveIT -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl project-service -am test -Dtest=IssueTypeDeletionIT -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl workflow-service -am test -Dtest=IssueTypeWorkflowReservationIT -Dsurefire.failIfNoSpecifiedTests=false
```

Эти же команды включены в соответствующие jobs PR CI. Проверки используют PostgreSQL и управляемые fake gRPC journal/pin/receipt для инъекции отказов, гонок и потери ответа; gateway отдельно проверяет реальный gRPC ответ с details больше 8 KiB и конечный REST body.
