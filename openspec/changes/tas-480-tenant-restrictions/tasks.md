## 1. Контракт и команды

- [x] 1.1 Проверить свободную TAS-480, зависимости, текущую схему и контракт; вынести A34/A42 в связанную TAS-535 и зафиксировать принятую baseline.
- [x] 1.2 Добавить отдельные причины, versioned pause/resume/platform-block с current authority, durable replay, audit/outbox и additive migrations.
- [ ] 1.3 Добавить RPC/REST и текущие restriction DTO/primary authority; проверить совместимость потребителей.

## 2. Доступ и billing

- [x] 2.1 Применить текущие root причины в capabilities/entitlement потребителей с read-флагом и сохранением management/financial paths.
- [x] 2.2 Оградить новые autopay admissions текущими root restrictions; сохранить accepted reconciliation и независимый paid period.

## 3. Проверки и завершение

- [ ] 3.1 Проверить success/refusal, scope, versions/concurrency/retry, rollback/RLS/audit, независимые причины и expiry/autopay scenarios на своих PostgreSQL/fixtures.
- [ ] 3.2 Выполнить manifest/IT потребителей, owned REST/mTLS E2E, docs/strict validation и review.

## Workflow follow-up

Синхронизировать specs, архивировать завершённый change; дождаться current-head CI/AI review, повторить affected E2E и выполнить разрешённый merge. Не считать live PSP E2E пройденным без sandbox capability.
