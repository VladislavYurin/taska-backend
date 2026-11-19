# Метки и наблюдатели tenant (TAS-363)

Профиль `tenant-storage` обслуживает существующие публичные label/watch RPC и REST
через native SQL и текущие primary facts. Legacy профиль сохраняется отдельно; при
ошибке нового источника перехода к legacy repository или recipient snapshot нет.

## Доступ и контракты

Gateway передаёт projectId исходного REST-пути в новые additive поля request body
в [issue-service.proto](../grpc-common-lib/src/main/proto/v1/issue-service.proto).
Issue-service сверяет его с неизменным проектом задачи до операции. Существующий
публичный REST и форма DTO сохранены; старые direct RPC без projectId не активируют
новый native endpoint. Перед включением обновить gateway/stubs вместе с producer.

Используются `LABEL_READ`, `LABEL_CONFIGURE`, `LABEL_ATTACH`, `LABEL_DETACH`,
`WATCHER_READ`, `WATCHER_SELF`, `WATCHER_MANAGE`. O/T/A настраивают project labels
и управляют чужими watchers; MEMBER привязывает/снимает метку и подписывает себя;
VIEWER читает. Текущие scopes, membership, project ACL, lifecycle, ограничения
tenant, архив проекта и применимые flags не подменяются ролью из запроса. Отдельный
WEB_GLOBAL administrative путь требует текущих GLOBAL_ADMIN/MFA и транспортных
полномочий; обычный PAT не наследует его по строке роли. Bypass аудитируется.

Перед watch целевой UUID проверяется по ACTIVE membership этой компании, текущему
project grant (либо O/T) и ACTIVE account. При unwatch разрешено убрать собственную
сохранённую связь уже неактивного target: это не создаёт подписку и не даёт ему
доступ. List/count/page и состояние self учитывают только текущих допустимых
watchers; фильтрация выполняется до пагинации. Настройки default/max page берутся
из `IssueProperties`. Порядок — createdAt desc, id desc.

Панель задачи, GET issue и resolve по ключу получают scoped labels/watchers.
Профили watchers не обогащаются из старых событий: DTO сохраняют UUID, а optional
displayName/avatar не заполняются прежним профилем. История содержит UUID и метаданные,
а не снимки email/имён или текст задачи. Search/board TAS-344 этим изменением не
активируются и не переключаются на legacy при отсутствии native реализации.

## Хранилище и эффекты

Новая миграция `issue-labels-watchers-0024` добавляет `issue_labels` и
`issue_watchers` с FORCE RLS/audit, tenant-qualified PK/unique и составными FK
tenant/project/issue/label. Применённые changesets не меняются. Межсервисный user FK
не создаётся: membership/account подтверждают владельцы своих данных.

Attach блокирует label row, затем issue; soft delete сериализуется с attach на
той же label row. Soft-deleted label больше не видна в назначениях, повтор delete
возвращает сохранённый timestamp; новая одноимённая метка не восстанавливает старые
связи. Watch/unwatch сериализуются по issue row. Фактическое изменение, версия issue,
history и outbox фиксируются вместе; повтор не создаёт второй эффект. Watcher
history использует существующий тип UPDATED с operation WATCHED/UNWATCHED, а label
history — LABEL_ADDED/LABEL_REMOVED.

Новые события содержат tenant envelope, projectId/issueId, labelId либо watcherUserId,
actorUserId, operation и version. Контент, профили и доверенный список recipients
не публикуются. SQL-транзакция не держится во время primary RPC; Permit привязан к
actor/tenant/project/action и ограничен сроком. Межсервисная проверка не является
распределённой транзакцией: доставка обязана повторять актуальную проверку перед send.

## Current recipients и эксплуатация

[issue-metadata-access.proto](../grpc-common-lib/src/main/proto/v1/issue-metadata-access.proto)
определяет private RPC с отдельными message-bound guards, вне legacy registry:

- `ProjectRecipientService.Check`: только issue-service SERVICE, purpose membership,
  scopes memberships:read/profiles:read, до 100 UUID. Project-service читает свои
  grants и root membership, затем существующий auth directory фильтрует ACTIVE
  accounts; наружу возвращаются только UUID. content_read дополнительно сохраняет
  current restrictions и READ_WHILE_RESTRICTED.
- `IssueRecipientService.Resolve`: только notification-service SERVICE, purpose
  NOTIFICATION_DELIVERY, scopes read/notifications:recipients. Tenant берётся из
  проверенного контекста; issue должен существовать в нём. Страница до 100 сохранённых
  watcher candidates фильтруется current eligibility. Непустой cursor требует
  продолжения даже при пустом результате страницы. Это watcher audience; другие
  audiences и исключение автора определяются политикой соответствующего события.

Worker должен иметь явные trust edges; отсутствие trust/primary закрывает вызов.
Нельзя выдавать эти полномочия из PAT, nodeId или payload очереди. Запуск общей
tenant transport/delivery, каналы и retry workers остаются TAS-321/325/326; этот PR
не отправляет tenant events через legacy fallback и не доказывает email/Telegram
delivery. Существующий native outbox остаётся durable границей производителя.

`TenantIssueMetadataIntegrationTest` проверяет реальные PostgreSQL/app LOGIN/RLS/FK,
конкурентность и rollback с управляемыми primary ports. Owned
`python3 scripts/harness/issue_metadata_e2e.py` проверяет семь приложений, REST,
primary auth/root/project/account и подписанный SERVICE resolver. Общая stage не
используется; reports/material/state остаются в ignored target, cleanup — только своих
ресурсов. Перед merge/deploy нужны отдельные полномочия пользователя.

`metadata-http-timings.json` сохраняет длительности, HTTP status, метод и path с
нормализованными UUID без query/body/headers. Он пишется в собственный каталог
прогона и при отказе; cleanup выполняется независимо от записи диагностики.
Report содержит max/p95 длительности mutations. При проверке нестабильности
выполнять последовательные полные прогоны на одном чистом head с новыми fixtures;
не заменять проверяемые статусы повтором записи после неоднозначного timeout.
