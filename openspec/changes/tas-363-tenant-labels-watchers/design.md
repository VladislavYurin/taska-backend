# Design

## Context

Native IssueApiConfiguration/PrimaryIssueAccess уже используют primary identity и project policy. ProjectLabelsRpc читает project_labels для context; полноценные label/watch RPC существуют только в legacy профиле. ProjectDirectoryService.Profiles отдаёт только ACTIVE account после текущей membership-проверки, доступен project-service с purpose membership.

## Goals / Non-Goals

**Goals:** существующие публичные операции, tenant/project FK и RLS/audit, текущий target access, атомарные эффекты и безопасный resolver recipients.

**Non-Goals:** общая Kafka tenant transport/delivery, inbox/preferences, самостоятельный search/board TAS-344, offboarding и полный user-profile tombstone TAS-496, shared stage, merge/deployment. Закрытые native search/board не переключаются на legacy.

## Decisions

- Отдельная новая issue migration после 0022; не переписывать применённую schema. Составные FK включают tenant/project/issue/label. Добавление tenant таблиц сопровождается protect-table/audit v2.
- SQL-only store фиксирует row locks, мутации, issue version, историю и body-free outbox в одной транзакции. Primary RPC выполняются до транзакции; краткий Permit ограничивает actor/tenant/project/action и срок. Label attach сериализуется с soft delete по label row, затем issue row; watcher — по issue row.
- Labels configure/read/attach и watchers self/manage/read используют соответствующие Action, а не общий ISSUE_UPDATE. Project archived и текущие tenant restrictions сохраняются. Подтверждённый administrative path не выводится из роли PAT.
- ProjectRecipientService.Check ограничен 100 UUID, issuer issue-service, purpose membership и scopes memberships:read/profiles:read. Project owner подтверждает свой grant с совпадением root_membership_version текущей membership: восстановление участника не оживляет старый grant. Root — ACTIVE membership/O/T, auth directory — ACTIVE account. Возвращаются только UUID, без профилей.
- List watchers фильтрует текущие UUID bounded batch до page/count. Notification resolver возвращает страницу кандидатов с cursor и current eligibility; service authority NOTIFICATION_DELIVERY не превращается в пользовательские полномочия и не разрешает мутации.
- Новые events содержат tenant envelope, issue/project/resource UUID и version, без контента и recipient snapshot. TAS-321/325/326 подключат transport/delivery к current resolver; существующая legacy доставка не получает tenant events через fallback.
- Existing public IssueService RPC/DTO остаются; новые internal RPC additive. Новые комментарии/JavaDoc по-русски, imports явные.

## Risks / Trade-offs

- Для текущего count необходима проверка всех кандидатов bounded batch; внешний вызов имеет deadline, unavailable не превращается в частичный достоверный ответ.
- Read/commit между сервисами не образуют distributed transaction: актуальные primary facts ограничены сроком, проверяются перед SQL, отзыв между enqueue/send требует повторного resolver у delivery worker.
- Rollout: новые migration и producer/consumer stubs до включения endpoints/trust; отсутствие trust/primary source закрывает операции. Миграции/данные и результаты прогонов не откатываются удалением общей БД.
