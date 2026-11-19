# Tasks

## 1. Реализация
- [x] 1.1 Подтянуть develop, проверить зависимости/свободу, взять TAS-473 в работу и текущий спринт.
- [x] 1.2 Добавить contracts/migrations Root operation/receipt и billing fence.
- [x] 1.3 Реализовать current policy, Root atomic transfer/replay/recovery и billing prepare/finish с fencing.
- [x] 1.4 Подключить публичный REST/gRPC, private guards и безопасный worker; синхронизировать документацию.

## 2. Приёмка
- [x] 2.1 Пройти PG/RLS/transaction/race/fault tests, consumer checks и явные IT через harness.
- [ ] 2.2 Пройти owned внешний E2E с current primary, idempotency/financial fencing и cleanup.
- [ ] 2.3 Открыть русский PR; дождаться CI/AI review актуального head, разобрать замечания и повторить affected E2E после review.
- [ ] 2.4 Синхронизировать/архивировать OpenSpec и передать проверяемый PASS без merge/deploy.
