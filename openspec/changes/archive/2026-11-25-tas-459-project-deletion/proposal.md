# Proposal

## Why
TAS-459 завершает lifecycle проекта: develop f1d92925 поддерживает архивирование и квоты, но запрещает физическое удаление. После слияния TAS-367 доступны повторяемое удаление объектов и подтверждение освобождения storage usage.

## What Changes
- Отдельная подтверждаемая, версионированная операция PROJECT_DELETE с persisted intent, текущим ACL/capability и actor-bound idempotency.
- Закрытие новых изменений, повторяемая очистка issue/storage/workflow и проектных данных с primary proof; квоты освобождаются только после durable результата владельца.
- REST/proto и версии проекта на проводе; прежний DELETE проекта сохраняет архивирование.

## Capabilities
### New Capabilities
- `project-deletion`: необратимое удаление своего проекта, устойчивое к повтору и сбою, с сохранением границ tenant и квотных tombstone.
### Modified Capabilities
Нет.

## Impact
project-service владеет операцией; issue-service — задачами, файлами и lifetime counter; workflow-service — привязками и проектными определениями; tenant-service — usage. api-gateway публикует REST. Только аддитивные миграции. Shared stage, merge и deployment не входят в поручение.
