# Spec Delta

## Purpose

Передавать владение компанией и финансовые полномочия текущему активному участнику без устаревшей проверки оплаты, дублей и повторного использования старого согласия.

## ADDED Requirements

### Requirement: Передача проверяет текущие полномочия
Система SHALL требовать текущую разрешённую identity/credential (для web — действующую сессию), явное подтверждение, ожидаемую tenant version и ACTIVE target того же tenant. Обычный caller SHALL быть текущим owner с paid coverage и transfer flag. Owner pause сама SHALL не запрещать передачу; FREE, platform block, dispute и прочие ordinary ограничения SHALL отклонять её. B02 SHALL проверяться текущим primary GLOBAL_ADMIN/MFA с аудитом.

#### Scenario: Разрешённая передача
- **WHEN** owner оплаченной ACTIVE компании подтверждает передачу своему ACTIVE MEMBER
- **THEN** target становится owner/TENANT_ADMIN, прежний owner остаётся TENANT_ADMIN и теряет финансовые полномочия

#### Scenario: Недопустимый target или policy
- **WHEN** target чужой/неактивный, caller не owner либо ordinary policy запрещает передачу
- **THEN** owner/роли/версии/финансовая история не меняются

### Requirement: Передача сериализуется с актуальной оплатой
Система SHALL удерживать durable billing fence текущего coverage до подтверждённого Root outcome. Root SHALL проверить время и текущие tenant/membership versions под собственным lock. Конкурирующая FREE PREPARED SHALL не давать устаревшее разрешение передачи.

#### Scenario: Отзыв membership или истечение покрытия
- **WHEN** target отозван либо fence/paid coverage истекли до Root mutation
- **THEN** передача не применяется; финансовое ограждение снимается только по проверенному COMMITTED/CANCELLED receipt, а не по истечению часов

#### Scenario: Конкурирующая финансовая мутация
- **WHEN** coverage меняется одновременно с передачей
- **THEN** subscription lock/fence определяет порядок и не допускает Root commit по уже отозванному покрытию

### Requirement: Повтор сохраняет единственный эффект
Система SHALL фиксировать owner, обе admin-роли, версии, receipt, аудит и outbox в одной Root transaction. Ключ SHALL быть связан с actor/credential/tenant/телом/версией; новый payload под тем же ключом SHALL конфликтовать.

#### Scenario: Concurrent и повтор
- **WHEN** одинаковая передача выполняется конкурентно либо ответ потерян
- **THEN** сохраняется один Root effect и один billing revoke; допустимый повтор возвращает исходный receipt

### Requirement: Старое согласие не передаётся новому владельцу
Система SHALL немедленно использовать owner_version для финансовых допусков и durable revoke старого mandate. Worker SHALL восстанавливать только завершение/отмену, не новую передачу. Принятые PSP факты, счета и immutable FREE receipts SHALL сохраняться.

#### Scenario: Lost reply и новое согласие
- **WHEN** после Root commit потерян ответ billing Finish и новый owner позже оформил собственное согласие
- **THEN** recovery отключает только старый owner/version mandate и не переписывает новое согласие или историю
