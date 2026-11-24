# Design

## Context

Issue DB владеет задачами, attachment manifest и metadata; tenant DB — reservation/effects. TAS-347 уже задаёт reserve/prepare/materialize/commit/activate и versioned S3. REST и proto остаются авторитетными контрактами. Реализация начата до появления OpenSpec в develop и сверена с критериями TAS-365 при обновлении базы.

## Goals / Non-Goals

**Goals:** долговечный tenant/actor-bound intent, текущие primary policy facts, однократность при повторах/конкуренции, восстановление после внешних эффектов и честный bypass.

**Non-Goals:** download/list/delete, аватары, shared stage, автоматический обход всех tenant, административные PAT/OAuth и merge/deploy.

## Decisions

- Добавить issue changeset 0023 с FORCE RLS/audit и неизменяемыми identity/версиями; предыдущие миграции не переписывать. Tenant+actor+request key идентифицирует intent, изменённое тело конфликтует.
- Новое optional max_attachment_bytes в PlanVersion берётся из существующего immutable catalog column. Accepted intent сохраняет cap/version. Gateway передаёт project ID и ключ повтора; старый legacy transport остаётся совместимым.
- Primary issue policy проверяет auth/tenant/billing/project/issue. Приватный CheckAdmission сохраняет исходный credential через подписанный hop; отсутствие источника закрыто. Реальный primary GA/MFA проверяется отдельно от project membership.
- Перед capability вызываются prepare и recordUploadWindow. HEAD выбирает staging version, затем короткая транзакция фиксирует её. Сетевые эффекты не выполняются внутри DB transactions.
- COPY разрешён единственному durable claim. Claim и owner cancel используют один advisory lock. Неизвестный COPY не повторяется; positive final proof проверяет metadata operation/source version. Это предпочитает удержанный резерв риску позднего неучтённого объекта.
- Owner materialize измеряет точные версии через HEAD/GET SHA-256. Root COMMIT опирается на primary receipt. Owner activate сохраняет attachment из durable upload intent в той же transaction, что CONFIRMED/outbox, включая внутренний Activate от quota root; повтор идемпотентен.
- Одинаковый проверенный principal может разделять только выполняемую confirm-сагу своего intent. Policy проверяется до присоединения каждого запроса; завершённые результаты не кэшируются, число выполняемых саг ограничено. Межпроцессная корректность остаётся в DB/receipts.
- ReconcileUploads ограничен tenant/cursor/100 intent и техническим SERVICE purpose. Он не выдаёт новые capabilities, не превращает PAT в SERVICE и не сканирует общий bucket.

## Risks / Trade-offs

Неизвестный COPY без последующего положительного proof может удержать резерв до операторского расследования. Versioning должен быть включён оператором до включения API; приложение не меняет общий bucket. Additive migration разворачивается до API, RPC trust edges и catalog field должны быть доступны всем участникам. При откате кода новые записи/резервы сохраняются; старый бинарник не должен отменять неизвестные новые COPY — workers необходимо обновлять согласованно. Технический reconcile не является встроенным планировщиком. Локальные E2E проверяют собственные fixtures, а не deployed stage.
