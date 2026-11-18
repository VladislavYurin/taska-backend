## Context

Tenant registry уже хранит version и restriction_version; rename задаёт образец tenant lock/idempotency/audit. Membership authority возвращает единый SQL snapshot, TenantDecisionFacts/tenant capabilities пока имеют пустые независимые ограничения. CapabilityEvaluator уже различает OWNER_CONTROL, CONTENT_READ и READ_WHILE_RESTRICTED (default true). Autopay имеет обязательную policy и fail-closed default; accepted attempts сверяются отдельно от нового dispatch.

## Decisions

- Взять утверждённые pause/resume/setPlatformBlock DTO/RPC из docs/b2b/contracts с прежними wire numbers. Pause требует confirmed; resume без body; platform block требует enabled/reason/confirmed. Admin reason сохранять только в закрытом audit, не публиковать raw reason в событиях или ошибках.
- Owner/текущая membership и версия проверяются под tenant lock; административная authority проверена transport по primary account/MFA/grant/target. В одной транзакции менять отдельную причину, tenant version/restriction_version, audit, outbox и frozen receipt. Replay сначала текущая identity/owner/admin, затем receipt, затем новые business gates; no-op не меняет версию и событие.
- OWNER_PAUSE и PLATFORM_BLOCK имеют стабильный source_id=tenantId; прочие source rows независимы. Clearing одной строки не трогает остальные. Нельзя оплачивать/снимать причины чужим command path.
- Передавать current independent restrictions/version в bounded membership authority и tenant DTO; tenant list использует безопасный actor-scoped DB function. Capability inputs используют current root snapshot, не event-only cache. Content restrictions объединяются с billing entitlement, не переписывают paidThrough.
- READ_WHILE_RESTRICTED по умолчанию true; при false deny content для ordinary members, сохраняя registry/membership lifecycle и финансовые разрешённые пути. GLOBAL_ADMIN имеет свой проверенный аудитируемый путь.
- Autopay получает обязательный текущий root restriction guard перед новым admission/dispatch; already accepted reconciliation не блокируется паузой. Resume не вызывает billing command, не продлевает период и не создаёт платёж.
- Новые migrations добавляются после применённых changesets, с RLS и audit/receipt protection. Тесты используют свои PostgreSQL и сервисы; не включают реальные PSP charges.

## Risks / Trade-offs

Новый root snapshot должен быть распознаваем потребителями; отсутствие обязательной свежей restriction authority — UNAVAILABLE. Проверить downgrade/старый root и общих RPC потребителей. Нет межсервисной транзакции с PSP: повторная admission проверка перед внешним эффектом и существующая UNKNOWN reconciliation сохраняются.

## Open Questions

TAS-535: разрешать ли NEW resume владельцу под platform block/dispute и какой notification/directory scope нужен при закрытом content. До решения действует OWNER_CONTROL из принятой access-matrix; существующее доступное management чтение не ужесточается. Это отдельное предложение изменить бизнес-правило, не разрешение изменить его в TAS-480.
