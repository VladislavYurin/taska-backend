# Design

## Context

Мотивация — proposal.md. Issue-service владеет attachment_uploads, manifest, metadata, resource receipt и storage outbox; tenant-service владеет единственным byte ledger. StorageQuotaOwner уже выполняет DELETE_PENDING → S3 purge → DELETED, Root.releaseUsage проверяет primary proof. Эти primitives переиспользуются.

## Goals / Non-Goals

Цель — открыть native read/delete, не ослабив исходную привязку upload к uploader. Legacy профиль, project offboarding и avatar namespace не переделываются. Общий стенд не используется для мутаций.

## Decisions

1. Native downloadPath ведёт на authenticated gateway content route. Gateway запрашивает metadata и ограниченные chunks (не более 1 MiB) через additive unary RPC. Каждый chunk получает новый проверенный gateway context и current issue/project authorization. S3 reader запрашивает конкретную final version и проверяет точный размер Range. Так нет публичной capability с живущим после revoke S3 URL, а память ограничена chunk.
2. Native REST передаёт все path IDs в RPC. Read использует typed ATTACHMENT_READ, delete — ATTACHMENT_DELETE и existing primary facts. Upload policy не используется как разрешение direct delete. Original uploader restriction сохраняется для upload; принятое удаление получает отдельную purpose-bound policy.
3. Новая additive Liquibase migration создаёт tenant-bound deletion jobs с immutable identity, FORCE RLS, lease epoch, retry time и terminal state. Acceptance атомарно сохраняет job, DELETE_PENDING и outbox; внешние эффекты выполняются после transaction. DELETE 204 означает durable принятие. Metadata и receipt не удаляются.
4. Worker claim ограничен batch 100, lease 60s; попытка ограничена временем меньше lease. CAS epoch защищает complete/retry. Worker вызывает existing owner.deleteConfirmed(DIRECT_DELETE), затем additive private Root ReleaseStorageUsage RPC и завершает job. Ошибка сохраняет used bytes и durable retry. Повторные ledger effects идемпотентны.
5. STORAGE_CLEANUP SERVICE обязан иметь current issuer/identity/scopes и bound accepted job. Production PROJECT_CLEANUP закрыт до TAS-459. Scheduler включается явно с bounded tenant worklist и ManagedServiceContexts; expired upload reconcile сохраняет тот же tenant/purpose. Никаких глобальных bucket scan и avatar cleanup.
6. Метрики используют ограниченные labels без tenant/user/file identifiers. Runbook описывает worklist, cursor, PUT window, retry и DATA_LOSS. Evidence и credentials остаются вне Git.

## Risks / Trade-offs

- Смешанный rollout контрактов → сначала additive RPC/root и migration, затем native routes и opt-in worker; неизвестный метод закрывает операцию.
- Отзыв после уже отправленных bytes → вернуть их невозможно; новый chunk проверяет доступ до выдачи, lease ≤30s.
- Повторная загрузка staging до истечения PUT window → owner удерживает bytes и retry до конца существующего 300s окна.
- S3 failure/unknown outcome → сохраняется DELETE_PENDING и ledger расход; повтор использует immutable keys/versions и primary proof.
- Недоступный current authority → public операция закрывается; accepted техническое продолжение использует свежую SERVICE authority и durable intent.

## Migration Plan

Не переписывать применённые changesets. Добавить новую таблицу/ограничения и transport routes, проверить существующие metadata при upgrade. При rollback остановить worker и закрыть новые native методы, сохранив jobs/receipts/ledger; destructive rollback данных не выполняется.
