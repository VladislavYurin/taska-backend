# Design

## Context

См. proposal.md. Два Reactor timeout в StorageQuotaStore.authorize/remote сейчас оставляют TimeoutException. ProjectQuotaGrpcService преобразует произвольную ошибку в INTERNAL. Gateway преобразует UNAVAILABLE и gRPC DEADLINE_EXCEEDED в 503 DEPENDENCY_UNAVAILABLE только у Problem-маршрутов; upload-url пока сохраняет legacy UNAVAILABLE/503 и DEADLINE_EXCEEDED/504. Policy вызывает issue CheckAdmission через managed RPC, billing отдаёт entitlement, owner подтверждает storage proof. Конкретный dependency первоначального случайного сбоя из TAS-547 не установлен.

## Goals / Non-Goals

Устранить воспроизводимый переход собственного dependency deadline в INTERNAL. Не менять общий gRPC mapper, другие project/seat ошибки, сроки, authority, алгоритм учёта или схему данных. Не добавлять retry HTTP 500 или автоматическую компенсацию неизвестного результата.

## Decisions

Преобразовать только TimeoutException на двух локальных dependency-границах в DomainException UNAVAILABLE с существующими безопасными phase codes. Остальные DomainException и gRPC statuses сохраняются. На trusted POST upload-url статусы UNAVAILABLE/DEADLINE_EXCEEDED выбирают Problem/503 DEPENDENCY_UNAVAILABLE и безопасное сообщение. Legacy-профиль, другие маршруты и ACL/quota/integrity отказы не меняются. Это учитывает внешний gRPC deadline 5s, способный сработать раньше внутреннего deadline.

Native fault injection выявила дополнительную границу до root admission: PrimaryAttachmentPolicy запрашивает текущие primary facts и catalog plan через ManagedDomainReads, чей deadline 5s также выбрасывает TimeoutException. Нормализация добавляется в attachment policy, с тестом виртуального времени и сохранности бизнес-ошибок; общий ManagedDomainReads и остальные домены не меняются.

Детерминированные регрессии используют управляемый незавершающийся источник для policy/billing/owner, реальные PostgreSQL app roles и mTLS root RPC. Проверяются initial/admission/replay, rollback, retained intent и повтор исходного ключа до и после восстановления. REST boundary проверяется отдельно и собственным native E2E со сбоем только собственного billing. Существующие transaction/outbox boundaries не перестраиваются; новые сетевые эффекты в БД-транзакции не добавляются.

## Risks / Trade-offs

Неизвестный root/owner результат нельзя трактовать как отсутствие reservation → читать сохранённую операцию и повторять тот же ключ. Wire deadline и Reactor deadline могут соревноваться → оба результата должны сохранять REST 503, без ослабления deadlines. Реальный первоначальный медленный вызов может оказаться иным → не заявлять его установленным по одному совпадению времени.

## Migration Plan

Миграции и новые контракты не требуются. Обновить tenant-service штатной выкладкой после разрешённого merge; при откате persisted intent/reservation сохраняются. Общий стенд не используется как fault fixture.
