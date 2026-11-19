# Proposal

## Why

TAS-363 / PR #337 реализована на develop a0499412, но передача остаётся PARTIAL из-за повторяющихся gateway deadline во внешнем E2E. Требуется установить и устранить причину, сохраняя текущую авторизацию. Изменение не слито и не развёрнуто.

## What Changes

- Повторно использовать неизменяемую проверенную модель trust snapshot только при совпадении SHA-256 заново прочитанных bytes.
- Сохранить немедленные expiry, rollback, reused-generation, corruption, removal и revocation проверки; не кэшировать решения доступа или primary facts.
- Добавить регрессии безопасности повторного использования и приватные HTTP latency measurements в owned metadata E2E.
- Повторить проверки, CI, AI review и последовательные E2E после review; закрыть ограничение передачи только по фактическим результатам.

## Capabilities

### New Capabilities

Нет: это оптимизация существующего trust parser и диагностик harness без изменения внешнего поведения; skip_specs=true.

### Modified Capabilities

Нет: REST/proto/schema, deadlines, grants, scopes, replay и бизнес-правила не меняются.

## Impact

grpc-common-lib ManagedRpcTrust и его потребители во всех сервисах; scripts/harness/issue_metadata_e2e.py. Только собственный локальный стенд. Результаты, JFR и ключи остаются вне Git. Merge/deploy запрещены поручением.
