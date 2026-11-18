# Tasks

## 1. Контракты и реализация

- [x] 1.1 Сверить Jira, зависимости и актуальный develop, взять TAS-365 в работу.
- [x] 1.2 Добавить typed catalog cap, RPC и REST параметры с проверкой потребителей.
- [x] 1.3 Реализовать durable upload intent, RLS/audit, текущий primary ACL и policy wiring.
- [x] 1.4 Реализовать pinned COPY, atomic activation и ограниченный purpose-bound reconcile.
- [x] 1.5 Документировать подключение, compatibility и UNKNOWN recovery.

## 2. Проверка и PR

- [x] 2.1 Пройти harness verify затронутых модулей, явные issue/project IT и проверить свежие XML/counts/skips.
- [x] 2.2 Пройти AttachmentUploadIntegrationTest, включая COPY unknown, сохранность чужого объекта и staging overwrite.
- [x] 2.3 Пройти public REST/mTLS/S3 E2E на итоговом head и подтвердить cleanup.
- [x] 2.4 Пройти docs, harness/config и strict OpenSpec validation.
- [x] 2.5 Открыть русский PR в develop, дождаться CI и AI review, разобрать замечания и повторить затронутые проверки после final review.
