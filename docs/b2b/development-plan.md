# B2B: очередь разработки после выполнения аналитики

Ревизия SA-2026-11-03, исходный код `af7ab57ead44304b89bbd47dabeeba45b761ce16`. Аналитика TAS-303/304/305/468/469/470/471 выполнена, пакет TAS-268 завершён. Это спецификация будущей реализации, не внедрённый B2B.

В карте 164 карточки: 127 технических подзадач на код/тесты/конфигурацию, 28 контейнеров результата, 8 выполненных аналитических карточек и 1 исключённая функция. Контейнеры не назначаются как отдельные большие задания.

Все рабочие карточки сохраняют формат «Что делать / Как сделать / Критерии приёмки / Зависимости и требования». Машиночитаемые связи и contract operationIds: [development-map.json](development-map.json). Бизнес-источник один — [Confluence](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880).

## Что отдавать первым

Волны ниже вычислены по непосредственным Blocks и завершению подзадач контейнера. Задача из следующей волны может стартовать, как только готовы именно её inputs; ждать всю волну не нужно. Это порядок зависимостей, не обещание сроков. Схему case/permit/retention receipts в admin_audit_log выполняет TAS-350; TAS-482 и TAS-520 принимаются после неё. Граф содержит 287 прямых Blocks; с завершением подзадач контейнеров — 415 связей, циклов нет.

### Волна 1

- [TAS-306](https://jira.ozero.dev/browse/TAS-306) — 01. Добавить проверяемый TenantContext в gRPC
- [TAS-320](https://jira.ozero.dev/browse/TAS-320) — 01. Создать PaymentProvider port и fake adapter

### Волна 2

- [TAS-307](https://jira.ozero.dev/browse/TAS-307) — 01. Реализовать tenant-транзакцию на R2DBC connection

### Волна 3

- [TAS-308](https://jira.ozero.dev/browse/TAS-308) — 02. Подготовить шаблон RLS и роли БД

### Волна 4

- [TAS-309](https://jira.ozero.dev/browse/TAS-309) — 03. Подключить интеграционные проверки RLS и connection pool
- [TAS-310](https://jira.ozero.dev/browse/TAS-310) — 01. Создать tenant-service и реестр компаний
- [TAS-318](https://jira.ozero.dev/browse/TAS-318) — 01. Добавить tenant envelope в события и outbox

### Волна 5

- [TAS-311](https://jira.ozero.dev/browse/TAS-311) — tenant-service: реализовать чтение и authoritative проверку membership
- [TAS-313](https://jira.ozero.dev/browse/TAS-313) — 01. Создать billing-service и каталог тарифов
- [TAS-329](https://jira.ozero.dev/browse/TAS-329) — 01. Изолировать схемы и repositories project-service
- [TAS-351](https://jira.ozero.dev/browse/TAS-351) — Оплата: надёжно принять проверенный webhook
- [TAS-462](https://jira.ozero.dev/browse/TAS-462) — 04. Типизированный глобальный реестр бизнес-флагов

### Волна 6

- [TAS-312](https://jira.ozero.dev/browse/TAS-312) — 02. Защитить владельца и последнего tenant admin
- [TAS-314](https://jira.ozero.dev/browse/TAS-314) — 02. Реализовать модель подписки и оплаченного покрытия
- [TAS-315](https://jira.ozero.dev/browse/TAS-315) — 01. Выпустить независимые tenant access/refresh сессии
- [TAS-330](https://jira.ozero.dev/browse/TAS-330) — 01. Изолировать workflow и выдавать копию шаблона компании
- [TAS-332](https://jira.ozero.dev/browse/TAS-332) — 01. Изолировать issue keys, counters и idempotency
- [TAS-472](https://jira.ozero.dev/browse/TAS-472) — Атомарно учитывать единственную FREE-возможность владельца
- [TAS-519](https://jira.ozero.dev/browse/TAS-519) — billing-service: валидировать коммерческий профиль и готовность live-оплаты

### Волна 7

- [TAS-316](https://jira.ozero.dev/browse/TAS-316) — 02. Передавать проверенный tenant из gateway во все RPC
- [TAS-321](https://jira.ozero.dev/browse/TAS-321) — 02. Ограничить directory участниками выбранной компании
- [TAS-337](https://jira.ozero.dev/browse/TAS-337) — Создание компании: выполнить возобновляемый provisioning
- [TAS-475](https://jira.ozero.dev/browse/TAS-475) — Создать неизменяемые счета подписки

### Волна 8

- [TAS-317](https://jira.ozero.dev/browse/TAS-317) — 03. Настроить service identity и ротацию gRPC ключей
- [TAS-319](https://jira.ozero.dev/browse/TAS-319) — 03. Выдавать versioned entitlement snapshot из billing
- [TAS-325](https://jira.ozero.dev/browse/TAS-325) — 02. Изолировать inbox, настройки и получателей уведомлений
- [TAS-331](https://jira.ozero.dev/browse/TAS-331) — 01. Рассчитывать календарные периоды и единственный счёт
- [TAS-366](https://jira.ozero.dev/browse/TAS-366) — 02. Общий аватар аккаунта: загрузка, замена и удаление
- [TAS-476](https://jira.ozero.dev/browse/TAS-476) — Создать журнал финансовых фактов и распределений
- [TAS-477](https://jira.ozero.dev/browse/TAS-477) — Обновлять единый профиль аккаунта через веб
- [TAS-478](https://jira.ozero.dev/browse/TAS-478) — Создание компании: отмена и автоматическое закрытие без первой оплаты

### Волна 9

- [TAS-322](https://jira.ozero.dev/browse/TAS-322) — 01. Считать места участников и резервы приглашений атомарно
- [TAS-326](https://jira.ozero.dev/browse/TAS-326) — 03. Сделать delivery workers и retries tenant-aware
- [TAS-333](https://jira.ozero.dev/browse/TAS-333) — 01. Разрешить владельцу переименовать компанию
- [TAS-349](https://jira.ozero.dev/browse/TAS-349) — Оплата: сделать payment attempts устойчивыми к повтору и timeout
- [TAS-359](https://jira.ozero.dev/browse/TAS-359) — Доступ: хранить versioned entitlement projection и ограниченный кэш
- [TAS-460](https://jira.ozero.dev/browse/TAS-460) — 04. Lifetime-лимит успешных созданий задач в проекте
- [TAS-463](https://jira.ozero.dev/browse/TAS-463) — 04. Привязать пользовательский PAT к одной компании

### Волна 10

- [TAS-323](https://jira.ozero.dev/browse/TAS-323) — 03. Отзывать доступ и назначения при suspension/removal
- [TAS-324](https://jira.ozero.dev/browse/TAS-324) — 02. Реализовать reservation/commit/release мест проектов
- [TAS-327](https://jira.ozero.dev/browse/TAS-327) — 03. Реализовать безопасное восстановление пароля
- [TAS-328](https://jira.ozero.dev/browse/TAS-328) — 04. Подтверждать web-вход GLOBAL_ADMIN кодом из почты
- [TAS-335](https://jira.ozero.dev/browse/TAS-335) — Приглашения: создать приглашение и зарезервировать одно место
- [TAS-348](https://jira.ozero.dev/browse/TAS-348) — billing-service: реализовать Stripe Checkout adapter и sandbox-сценарии
- [TAS-360](https://jira.ozero.dev/browse/TAS-360) — Доступ: реализовать общий вычислитель capabilities и причин отказа
- [TAS-483](https://jira.ozero.dev/browse/TAS-483) — Применять новые условия тарифа после уведомления
- [TAS-498](https://jira.ozero.dev/browse/TAS-498) — Привязать пользовательское OAuth-разрешение к одной компании

### Волна 11

- [TAS-334](https://jira.ozero.dev/browse/TAS-334) — Настройки: кэшировать версии флагов и entitlement snapshots
- [TAS-336](https://jira.ozero.dev/browse/TAS-336) — Приглашения: принять приглашение в компанию
- [TAS-338](https://jira.ozero.dev/browse/TAS-338) — Компании: выдать список доступных компаний пользователя
- [TAS-340](https://jira.ozero.dev/browse/TAS-340) — Проекты: применять tenant ACL во всех API и агрегатах
- [TAS-345](https://jira.ozero.dev/browse/TAS-345) — Администрирование: применять полный доступ GLOBAL_ADMIN
- [TAS-347](https://jira.ozero.dev/browse/TAS-347) — Хранение: учитывать reservations и подтверждённые байты
- [TAS-461](https://jira.ozero.dev/browse/TAS-461) — 05. Удаление аккаунта: предусловия и одноразовое подтверждение
- [TAS-473](https://jira.ozero.dev/browse/TAS-473) — Передать владение оплаченной компанией
- [TAS-479](https://jira.ozero.dev/browse/TAS-479) — Подтвердить смену email глобального аккаунта
- [TAS-481](https://jira.ozero.dev/browse/TAS-481) — Подключить почту первого GLOBAL_ADMIN и операторское восстановление

### Волна 12

- [TAS-339](https://jira.ozero.dev/browse/TAS-339) — Приглашения: resend, отзыв и истечение срока
- [TAS-341](https://jira.ozero.dev/browse/TAS-341) — Проекты: tenant-проверки архивирования и восстановления
- [TAS-342](https://jira.ozero.dev/browse/TAS-342) — Workflow: проверять компанию и права во всех RPC
- [TAS-346](https://jira.ozero.dev/browse/TAS-346) — Администрирование: ограничить queries и сводки явной областью
- [TAS-354](https://jira.ozero.dev/browse/TAS-354) — Подписка: менять тариф и отменять продление
- [TAS-356](https://jira.ozero.dev/browse/TAS-356) — Счета: сохранять реквизиты покупателя в неизменном snapshot
- [TAS-480](https://jira.ozero.dev/browse/TAS-480) — Компании: пауза владельцем и блокировка платформой
- [TAS-485](https://jira.ozero.dev/browse/TAS-485) — Администрирование: глобальные роли и блокировка аккаунтов
- [TAS-497](https://jira.ozero.dev/browse/TAS-497) — Публиковать effective capabilities и причины ограничений
- [TAS-499](https://jira.ozero.dev/browse/TAS-499) — Административные PAT/OAuth: выдача, область и уровни
- [TAS-500](https://jira.ozero.dev/browse/TAS-500) — UI: выбор компании и изоляция workspace между вкладками
- [TAS-510](https://jira.ozero.dev/browse/TAS-510) — UI GLOBAL_ADMIN: вход с email-кодом и путь восстановления
- [TAS-518](https://jira.ozero.dev/browse/TAS-518) — billing-service: реализовать очередь ручного финансового разбора

### Волна 13

- [TAS-343](https://jira.ozero.dev/browse/TAS-343) — Задачи: изолировать CRUD, назначение и переходы
- [TAS-350](https://jira.ozero.dev/browse/TAS-350) — Администрирование: внутренний аудит и безопасный повтор outbox
- [TAS-352](https://jira.ozero.dev/browse/TAS-352) — Оплата: однократно применить подтверждённый платёж
- [TAS-491](https://jira.ozero.dev/browse/TAS-491) — Доступ: подключить capabilities к операциям компании и участников
- [TAS-501](https://jira.ozero.dev/browse/TAS-501) — UI: самостоятельное создание FREE и paid-компании
- [TAS-502](https://jira.ozero.dev/browse/TAS-502) — UI: участники компании и управление приглашениями
- [TAS-503](https://jira.ozero.dev/browse/TAS-503) — UI: принятие приглашения новым и существующим аккаунтом
- [TAS-504](https://jira.ozero.dev/browse/TAS-504) — UI: название, владелец и пауза компании
- [TAS-507](https://jira.ozero.dev/browse/TAS-507) — UI: единые имя и аватар аккаунта
- [TAS-509](https://jira.ozero.dev/browse/TAS-509) — UI: пользовательские PAT и OAuth-подключения одной компании
- [TAS-511](https://jira.ozero.dev/browse/TAS-511) — UI GLOBAL_ADMIN: выдача и изменение административных разрешений
- [TAS-512](https://jira.ozero.dev/browse/TAS-512) — UI: подписка, квоты и объяснение ограничений

### Волна 14

- [TAS-344](https://jira.ozero.dev/browse/TAS-344) — Задачи: изолировать поиск, доску и пакетные счётчики
- [TAS-353](https://jira.ozero.dev/browse/TAS-353) — Оплата: сверять UNKNOWN/PENDING и пропущенные callbacks
- [TAS-357](https://jira.ozero.dev/browse/TAS-357) — Банк: учесть поступление EUR и подтвердить полную оплату
- [TAS-362](https://jira.ozero.dev/browse/TAS-362) — Задачи: изолировать комментарии, worklogs и расчёт времени
- [TAS-363](https://jira.ozero.dev/browse/TAS-363) — Задачи: изолировать метки, наблюдателей и их события
- [TAS-365](https://jira.ozero.dev/browse/TAS-365) — Вложения: выдать upload reservation и однократно подтвердить файл
- [TAS-482](https://jira.ozero.dev/browse/TAS-482) — Счета: формировать и скачивать приватные финансовые документы
- [TAS-513](https://jira.ozero.dev/browse/TAS-513) — UI: hosted checkout и подтверждённый статус оплаты
- [TAS-520](https://jira.ozero.dev/browse/TAS-520) — tenant/auth: реализовать retention policies, holds и повторяемый purge

### Волна 15

- [TAS-355](https://jira.ozero.dev/browse/TAS-355) — Подписка: выполнять продление с явным согласием на автосписание
- [TAS-358](https://jira.ozero.dev/browse/TAS-358) — Возвраты: заявка и полный возврат платежа подписки
- [TAS-364](https://jira.ozero.dev/browse/TAS-364) — Задачи: проверять доступ к обоим концам связи
- [TAS-367](https://jira.ozero.dev/browse/TAS-367) — 03. Вложения: защищённое скачивание и повторяемая очистка
- [TAS-484](https://jira.ozero.dev/browse/TAS-484) — Банк: разбирать поздние и нераспределённые поступления
- [TAS-486](https://jira.ozero.dev/browse/TAS-486) — Банк: вернуть нераспределённый излишек первой оплаты
- [TAS-488](https://jira.ozero.dev/browse/TAS-488) — Банк: выставить счет продления и обязательное напоминание
- [TAS-494](https://jira.ozero.dev/browse/TAS-494) — Удаление аккаунта: отзыв доступов и очистка членств

### Волна 16

- [TAS-372](https://jira.ozero.dev/browse/TAS-372) — 01. Экспорт рабочих данных компании с актуальными ACL
- [TAS-459](https://jira.ozero.dev/browse/TAS-459) — 04. Безвозвратное удаление проекта с освобождением квот
- [TAS-487](https://jira.ozero.dev/browse/TAS-487) — Администрирование: удаление глобального аккаунта с сохранением целостности
- [TAS-490](https://jira.ozero.dev/browse/TAS-490) — Споры: учитывать подтверждённые исходы и пересчитывать доступ
- [TAS-495](https://jira.ozero.dev/browse/TAS-495) — Доступ: подключить capabilities к задачам и вложениям
- [TAS-496](https://jira.ozero.dev/browse/TAS-496) — Показывать удалённого автора без прежнего профиля
- [TAS-514](https://jira.ozero.dev/browse/TAS-514) — UI: банковский счёт, реквизиты и ручная сверка
- [TAS-515](https://jira.ozero.dev/browse/TAS-515) — UI: смена тарифа, продление и согласие на autopay

### Волна 17

- [TAS-373](https://jira.ozero.dev/browse/TAS-373) — 02. Удаление компании: подтверждение и запуск offboarding
- [TAS-493](https://jira.ozero.dev/browse/TAS-493) — Доступ: подключить capabilities к project/workflow операциям
- [TAS-505](https://jira.ozero.dev/browse/TAS-505) — UI: запуск и скачивание рабочего экспорта
- [TAS-508](https://jira.ozero.dev/browse/TAS-508) — UI: предусловия и подтверждение удаления аккаунта
- [TAS-516](https://jira.ozero.dev/browse/TAS-516) — UI: заявки владельца на возврат и состояние финансового спора
- [TAS-517](https://jira.ozero.dev/browse/TAS-517) — UI GLOBAL_ADMIN: ручная сверка и финансовые решения

### Волна 18

- [TAS-361](https://jira.ozero.dev/browse/TAS-361) — Доступ: проверить переходы оплаты, expiry и READ_ONLY
- [TAS-474](https://jira.ozero.dev/browse/TAS-474) — Удаление компании: повторяемая очистка доменов и файлов

### Волна 19

- [TAS-369](https://jira.ozero.dev/browse/TAS-369) — 01. Чистый старт: схемы, initializer и фикстуры A/B
- [TAS-375](https://jira.ozero.dev/browse/TAS-375) — 04. Ограничить нагрузку на API по компании
- [TAS-506](https://jira.ozero.dev/browse/TAS-506) — UI: необратимое удаление компании с кодом

### Волна 20

- [TAS-370](https://jira.ozero.dev/browse/TAS-370) — 02. Чистый старт: manifest очистки S3, outbox и очередей
- [TAS-489](https://jira.ozero.dev/browse/TAS-489) — Распределять outbox, email и export jobs справедливо по компаниям

### Волна 21

- [TAS-371](https://jira.ozero.dev/browse/TAS-371) — harness: реализовать автоматизацию чистого B2B-запуска и smoke
- [TAS-492](https://jira.ozero.dev/browse/TAS-492) — Добавить диагностику tenant jobs, квот и сверки

### Волна 22

- [TAS-374](https://jira.ozero.dev/browse/TAS-374) — harness: реализовать восстановление tenant и автоматический restore drill

### Волна 23

- [TAS-376](https://jira.ozero.dev/browse/TAS-376) — infra: реализовать B2B deployment templates и проверки конфигурации

### Волна 24

- [TAS-377](https://jira.ozero.dev/browse/TAS-377) — 01. Включить CI-матрицу изоляции компаний A/B

### Волна 25

- [TAS-378](https://jira.ozero.dev/browse/TAS-378) — 02. Сквозная приёмка billing с fake clock и сбоями

### Волна 26

- [TAS-379](https://jira.ozero.dev/browse/TAS-379) — CI/security: реализовать регрессии и автоматический B2B release gate

## Распределение ответственности

- Foundation: trusted context, R2DBC/RLS, tenant registry, sessions/grants.
- Domain: project/workflow/issue/schema/queries, quota admission и файлы.
- Billing: catalogue/invoice/ledger/fake, затем settlement/PSP/WIRE/refund/reconciliation.
- UI: типизированные контракты позволяют готовить компоненты/mocks заранее; integration acceptance требует backend dependencies.
- Platform/QA: worker fairness, diagnostics, cutover/restore automation и regressions.

Каждый PR охватывает одну подзадачу; сначала схема/контракт/локальный happy+deny/race test, затем её интеграция. У каждой карточки свои dependencies и ссылки, полный бизнес-документ в описание не копируется.

## Принятые решения и отложенное подключение

D250: Stripe выбран, реальное подключение отложено до внесения operator merchant configuration; sandbox/fake готовятся сейчас. D251: окончательный partial chargeback снимает связанное покрытие, новая полная оплата даёт новый месяц. D252: решение/отправка банковского возврата ≤5 рабочих дней, комиссия Taska. D253: [retention ES_V1 и доступ к документам](retention-policy.md) принят по делегации. [C02/C03/C05/C06](commercial-decisions.md) закрыты; TAS-518/519/520 реализуют эти правила. Уточнены schema inputs: TAS-350 → TAS-482 и TAS-350 → TAS-520; первые независимые задачи остаются TAS-306 и TAS-320.

Ручное разрешение запуска и независимый security sign-off описаны в [operations-design.md](operations-design.md); их нельзя объявить выполненными при закрытии аналитики. TAS-371/374/376/379 реализуют команды, тесты и configuration gates.
