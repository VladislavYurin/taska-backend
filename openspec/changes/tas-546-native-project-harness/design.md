## Context

ProjectAclLocal объединяет реальные Auth/Root/Billing/Project/Workflow/Issue/Gateway и app LOGIN/RLS, но часть SERVICE capability source edges добавлена только в производных WorkflowAclLocal/IssueAclLocal. Public project errors проходят существующий RestErrorMapper; fixture не должна переопределять его ради старого assertion. Recovery worklist сейчас указывает на company синхронного отказа, что допускает конкуренцию с её компенсацией.

## Goals / Non-Goals

Goals: полный воспроизводимый native project runner и сохранность strict isolation; минимальная согласованная source graph; честные негативные assertions и отдельная проверка recovery.
Non-goals: новая бизнес-политика, ослабление primary ACL/guards/deadlines, миграции, новый public REST/proto, общая stage и доставка Kafka.

## Decisions

Централизовать существующие минимальные SERVICE source edges в базовой owned fixture и убрать дубликаты из наследников. Проверять граф на уникальность и ограничения identity/purpose/scopes. Production allowlist и providers не подменяются.

Для негативных запросов использовать текущий wire contract и дополнительные SQL-проверки состояния; различать admission quota и duplicate resource. Синхронная компенсация получает отдельную owned company вне recovery worklist; unknown outcome использует исходную worklist company, один request key и исходную resource identity. Writes после неопределённого исхода вслепую не повторяются.

Наследники и consumers проверяются на согласованном результате TAS-363/365. Общие REST/proto/schema не меняются. Сервисные БД раздельные, WebFlux/R2DBC, transactional outbox и границы DB/remote effects остаются прежними.

Issue probe различает frozen create receipt и actor-specific read enrichment TAS-363: для пустой watcher fixture чтение обязано явно вернуть watched_by_me=false; все остальные поля GetByKey строго совпадают с исходным receipt.

## Risks / Trade-offs

Полный runner может обнаружить дополнительный дефект продукта: его нельзя скрывать изменением ожидаемого success, guard или deadline. Сначала отделить ошибку собственной fixture от подтверждённого runtime дефекта; нерешённые бизнес-вопросы оформить отдельно в Jira. Results/state/logs не входят в Git.
