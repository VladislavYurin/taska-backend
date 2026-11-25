# Spec Delta

## Purpose

Безопасно привязать загрузку вложения к текущему tenant, исходному actor и issue, сохраняя однократный учёт actual bytes при повторах и внешних сбоях.

## ADDED Requirements

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
- **WHEN** used bytes и активные reservations вместе исчерпали бизнес-лимит
- **THEN** новый upload обычного actor возвращает HTTP 422 с кодом QUOTA_EXCEEDED без нового расхода

### Requirement: Подтверждение сохраняет целостность и однократность

Система SHALL фиксировать конкретные версии staging/final, проверять фактические положительные bytes, MIME и SHA-256 и SHALL использовать существующие root admission/effects вместо второго счётчика.

#### Scenario: Повтор и конкурентное подтверждение
- **WHEN** confirm повторяется или выполняется конкурентно
- **THEN** один intent создаёт одно attachment, одну canonical final version и один COMMIT effect на actual bytes; metadata и owner CONFIRMED/outbox сохраняются атомарно

#### Scenario: Недопустимый размер или поздний confirm
- **WHEN** фактический размер превышает reserved либо reservation истёк до commit
- **THEN** confirm не создаёт attachment и не начисляет used bytes

#### Scenario: Подмена staging после выбора версии
- **WHEN** клиент перезаписал staging после её фиксации
- **THEN** final copy и SHA-256 относятся к исходной зафиксированной версии

### Requirement: Неизвестный результат сохраняет резерв

Система SHALL восстанавливать только собственный manifest и SHALL удерживать резерв до положительного доказательства завершения COPY и очистки при отмене.

#### Scenario: Потерянный ответ COPY
- **WHEN** COPY начат, но final version неизвестна
- **THEN** повтор не отправляет второй COPY, отсутствие HEAD не освобождает bytes, а отмена сериализована с claim COPY

#### Scenario: Ошибка metadata после root COMMIT
- **WHEN** S3 и root COMMIT завершены, но metadata transaction не сохранилась
- **THEN** повтор или purpose-bound техническое восстановление активирует одно attachment без повторного расхода

#### Scenario: Ограниченное техническое восстановление
- **WHEN** актуальный SERVICE с purpose STORAGE_CLEANUP и scopes write/storage:recover вызывает tenant-bound reconcile
- **THEN** обрабатывается не более 100 intent с cursor, а PAT либо иной purpose не получает таких полномочий

#### Scenario: Восстановление обнаружило нарушение целостности
- **WHEN** primary источник возвращает DATA_LOSS при обработке сохранённого intent
- **THEN** ответ reconcile увеличивает failed и отдельный data_loss, сохраняет учёт и не маскирует повреждение временной недоступностью

### Requirement: Административный bypass сохраняет учёт

Система SHALL требовать актуальное primary GLOBAL_ADMIN и web MFA, а для WEB_GLOBAL также MODIFY, и SHALL сохранять проверки существования и целостности объекта.

#### Scenario: Разрешённый обход бизнес-квоты
- **WHEN** подтверждённый администратор загружает целый существующий файл сверх бизнес-квоты
- **THEN** операция учитывает actual bytes один раз; текст роли в обычном PAT не предоставляет bypass
