# Tasks

## 1. Durable delete и byte ledger

- [ ] 1.1 Добавить deletion jobs, scoped policy и native delete с немедленным скрытием; проверить PostgreSQL/RLS, разрешённого другого участника, READ_ONLY и повтор без внешних эффектов при отказе.
- [ ] 1.2 Открыть bound ReleaseStorageUsage RPC и реализовать bounded lease/retry worker; проверить S3 failure, restart, stale epoch, однократный release и изоляцию, описать private контракт.

## 2. Защищённое чтение

- [ ] 2.1 Реализовать native list/downloadPath/content RPC и version-pinned bounded S3 range reader; проверить CONFIRMED-only, path/tenant mismatch, current ACL и сохранение upload policy.
- [ ] 2.2 Добавить gateway GET/HEAD/Range с повторной авторизацией chunks и authoritative OpenAPI; проверить точные bytes, Range/416, headers, revoke во время потока, legacy совместимость и описать API.

## 3. Эксплуатация и интеграция

- [ ] 3.1 Добавить opt-in tenant worklist scheduler для delete/expired uploads, метрики и runbook; проверить purpose/scopes, bound intent, bounded batch и отсутствие avatar/global scan.
- [ ] 3.2 Добавить owned native lifecycle E2E и harness selection; выполнить применимые проверки из verification.json, явные IT, native download/delete/retry/restart/изоляцию и физический cleanup с evidence вне Git.
- [ ] 3.3 Создать отдельный PR, разрешить подтверждённые замечания current-head AI review, повторить затронутые E2E на финальной версии и проверить docs/OpenSpec strict validation.

## Workflow follow-up

- Синхронизировать specs и архивировать только завершённый change; проверить архив.
- После readiness получить конкретное разрешение merge этого PR, проверить required checks и выполнить разрешённый merge в develop, обновить Jira.
