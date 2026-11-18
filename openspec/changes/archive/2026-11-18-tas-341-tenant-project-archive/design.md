## Context

Нативный профиль использует TenantDecisionFacts, TenantProjectStore, RLS и version projects. Authorize проверяет current root membership, effective ADMIN для owner/TENANT_ADMIN и отдельный ADMIN credential/grant с аудитом. Существующая защита последнего project ADMIN сериализована project lock.

## Decisions

- Добавить RestoreProject с собственным request DTO и optional expected_version/idempotency_key; ProjectResponse.version аддитивен. Native lifecycle требует оба поля, legacy archive сохраняет прежнее поведение.
- Публичный restore: POST /api/v1/projects/{projectId}/restore. Native DELETE и POST требуют strong quoted If-Match и canonical UUID Idempotency-Key; 428 без версии, 412 stale, 409 конфликт ключа. Версию возвращать в ProjectResponseDto.
- Получать первичные facts до SQL транзакции; внутри блокировать tenant project и заново вычислять project role. Restore допускает archived state, но не обходит lifecycle/policy. Last ADMIN guard остаётся без ослабления.
- В одной транзакции сохранять archived_at/version, tenant outbox и immutable response receipt с scope tenant/actor/key/action/project/version. Повтор проверяет текущий доступ и возвращает сохранённый ответ. Смена действия, проекта или версии под тем же ключом запрещена.
- Архив/restore не вызывает quota reserve/release и не удаляет данные. Повтор уже достигнутого состояния с новой актуальной версией не создаёт события/новой версии.
- Новая migration добавляет только таблицу квитанций с FK/RLS/append-only; применённые changesets не меняются.

## Risks / Trade-offs

Клиент native profile должен читать версию и передавать заголовки. Additive proto допускает поэтапный rollout, но restore требует обновлённый сервер. Legacy restore не включается автоматически. Исторические события ProjectArchived сохраняются, новое ProjectRestored передаёт только проект/actor/time.

## Open Questions

Нет: права, сохранение квоты и stale version заданы TAS-341 и действующей access-matrix. Изменение бизнес-правил не требуется.
