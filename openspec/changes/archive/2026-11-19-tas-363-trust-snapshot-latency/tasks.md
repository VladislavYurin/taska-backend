# Tasks

## 1. Причина и исправление

- [x] 1.1 Проверить актуальность develop/PR/Jira и воспроизвести нагрузку с приватным профилированием.
- [x] 1.2 Добавить регрессии reuse/immutability/expiry/tampering/revocation/rollback/concurrency и подтвердить падение до исправления.
- [x] 1.3 Устранить повторный parse без ослабления security checks; добавить приватные timings E2E и синхронизировать документацию.

## 2. Приёмка

- [x] 2.1 Пройти harness и security/integration проверки, сравнить allocation/latency с исходной версией.
- [x] 2.2 Повторить CI и AI review итогового head, разобрать замечания.
- [x] 2.3 Пройти последовательные owned E2E после review без timeout записей; подтвердить cleanup и передачу PASS.
- [x] 2.4 Архивировать завершённый change и передать результаты без merge/deploy.
