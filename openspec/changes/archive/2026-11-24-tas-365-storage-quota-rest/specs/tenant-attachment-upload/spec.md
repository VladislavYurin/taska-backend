## MODIFIED Requirements

### Requirement: Приём привязан к текущему доступу

Система SHALL проверять актуальные primary credentials/scopes, tenant/project/issue ACL, lifecycle, upload flag и типизированный catalog cap; недоступный источник SHALL закрывать операцию.

#### Scenario: Разрешённая новая загрузка
- **WHEN** текущий actor имеет write и доступ к существующему issue, а новые uploads разрешены
- **THEN** сервер сохраняет intent с исходными actor/tenant/project/issue, размером и сроком, резервирует bytes и выдаёт PUT только на собственный staging key после фиксации окна не длиннее пяти минут

#### Scenario: MIME не разрешён конфигурацией
- **WHEN** запрос нового upload содержит MIME вне storage.allowed-content-types
- **THEN** сервер возвращает контролируемый отказ до сохранения intent и reservation

#### Scenario: Чужой объект или отозванный доступ
- **WHEN** confirm содержит чужой key, issue или actor либо membership/ACL отозван
- **THEN** attachment и расход не появляются, а чужой объект не удаляется

#### Scenario: Бизнес-флаг выключен после приёма
- **WHEN** uploads выключены после принятого intent
- **THEN** новое admission запрещено, а принятая операция может завершиться по своему lifecycle с остальными текущими проверками

#### Scenario: Хранилище занято обычным actor
- **WHEN** used bytes и активные reservations вместе исчерпали бизнес-лимит и обычный actor вызывает POST attachment upload-url
- **THEN** сервер возвращает HTTP 422 application/problem+json с code=QUOTA_EXCEEDED, details quota.kind=STORAGE_BYTES и requestId без нового расхода; прочие ошибки и соседние legacy операции сохраняют свой формат
