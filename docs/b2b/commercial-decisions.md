# Коммерческий контур: выполненная аналитика и решения владельца

Исследование: 02.11.2026; решения владельца и делегированная политика: 03.11.2026 (D250–D253). Бизнес-источник — B08–B10/B17/B22 [единой спецификации](https://confluence.ozero.dev/pages/viewpage.action?pageId=60751880). O04/O08/O09 остаются идентификаторами групп, а не просьбой разработчику придумать бизнес-правило. Бизнес-вопросы C02/C03/C05/C06 закрыты ответами владельца и делегированным решением. Реальное подключение merchant отложено владельцем; это операционная конфигурация перед live, а не незавершённая аналитика. Этот документ не утверждает регистрацию продавца/merchant и не выдаёт sandbox за live.

## C01. PSP: сравнительный результат

| Критерий | Stripe | Adyen | Вывод для Taska |
| --- | --- | --- | --- |
| Hosted checkout | Checkout Sessions поддерживает redirect на страницу Stripe [1] | Pay by Link ведёт на hosted page, но документация рекомендует online integration как основной путь и не использовать Pay by Link как единственный [4] | Выбран Stripe Checkout (D250). Adyen — только исследованная альтернатива, не второй adapter в первой версии. |
| Server payment confirmation | HTTPS webhooks, подпись, async status, duplicates/order необходимо учитывать [2] | HMAC verification, отдельные ключи test/live [5] | Provider port нормализует проверенный fact; return URL не PAID, raw callback не trusted TenantContext. |
| Повторы команд | Provider idempotency поддерживается; не заменяет собственную долговечную operation запись [3] | Idempotency для API запросов, в пределах контракта провайдера [6] | Billing service хранит собственные бизнес-ключи; UNKNOWN сначала сверяется. |
| Возвраты | У provider есть pending/failed и возможность partial [7] | Конкретный adapter обязан нормализовать возврат и отдельно подтвердить финальный исход | Возможность PSP не расширяет продукт: Taska не предлагает subscription partial refund. Полный возврат ≠ отправленная команда. |
| Recurring | Встраивается через согласованный payment method/consent flow; доступность зависит от merchant/method | Token payments требуют сохранённого payment method и recurring lifecycle [8] | Taska хранит owner-specific consent; первая оплата не включает autopay. |
| Налоги, страны, методы, ставки | Доступность инструмента не подтверждает налоговый статус конкретного продавца | То же | Нужна подтверждённая commercial profile matrix. Не обещать все страны/методы на основании общей страницы PSP. |

Выбран **Stripe hosted Checkout** (D250); собственные invoice/period ledger и provider port сохраняются. TAS-348 реализует test-mode adapter. Подключение реального оператора/merchant владелец отложил; TAS-519 разрешает live только после provision подтверждённого профиля. Автоматический налог/Adaptive Pricing/PSP subscription calendar не включаются скрыто: конечная цена и Taska calendar rules сохраняются.

## C02. Stripe выбран; подключение live отложено (O08)

Планируемая страна продавца — Испания, B2B+B2C; базовая цена 20 EUR за месяц **включая** применимые налоги; прямой банк только EUR; USD через PSP по immutable FX snapshot. D250 выбирает Stripe, но не задаёт вымышленные seller name, tax ID, адрес, банк, merchant account или FX rate source. Эти значения вносит оператор при подключении реального приёма платежей; сейчас `livePaymentEnabled=false`. Sandbox имеет отдельные учётные данные и синтетические реквизиты.

Схема конфигурации готова: sellerProfileVersion, merchant/environment, market, buyerType, currency, paymentMethod, taxRuleId, documentTemplateVersion, refundPolicyVersion, bankBusinessCalendarId/version и enabled. Неподтверждённая комбинация скрыта/отклоняется до checkout; цена 20 EUR не повышается автоматически. Секреты задаются ссылками, не через Git или REST-ответы.

`FxQuoteProvider` возвращает EUR→USD decimal rate, источник, quotedAt, validUntil и source version. Неизвестный/просроченный источник запрещает **новые USD invoices**, EUR предлагается явно. ECB reference rates информационные, не скрытый transaction default [9]. Ответ владельца не менял согласованную доступность валют: запрет USD или EUR-only launch не принят. USD port и тесты остаются, live комбинация включается после настройки проверяемого FX source/maxAge. Нового исследования или выбора PSP разработчику не требуется.

## C03. Частичный внешний chargeback: снять связанное покрытие (D251 / O04)

Внешний частичный денежный факт сохраняется полностью и неизменно с original payment и evidence. До окончательной сверки — NEEDS_REVIEW, dispute READ_ONLY для затронутого покрытия и остановка новых autopay. После подтверждённого окончательного частичного проигрыша GLOBAL_ADMIN фиксирует `LOST_PARTIAL_COVERAGE_REVOKED`; dispute → LOST, **только связанное покрытие отзывается полностью**. Остаток старого периода не предоставляется пропорционально деньгам. Независимый действующий период не отзывается.

Возобновление требует новой полной оплаты и нового полного месячного периода по B06 (PSP successful payment; WIRE manual confirmedAt). Новая оплата не снимает независимые pause/block/другой dispute. Закрытый спор не остаётся вечной причиной READ_ONLY: если другого действующего покрытия нет, причина — NO_PAID_COVERAGE. Autopay остаётся отключён до нового согласия владельца. Продуктового partial refund, автоматического возврата остатка или второго расхода уже возвращённой PSP суммы нет. Повторы/out-of-order дают один coverage reversal и один финансовый факт.

## C04. Банковский разбор: технические решения приняты (O04)

| Случай | Реализация без дополнительного бизнес-решения |
| --- | --- |
| Неизвестный payer/назначение | UNALLOCATED ledger + OPEN/WAITING_EVIDENCE case; ручная привязка по проверенной выписке и invoice, никакой автоактивации по email/похожей сумме. |
| Дата без времени у deadline | Сохранять precision DAY и диапазон возможного receivedAt. Если диапазон пересекает deadline, запросить банковское подтверждение в case. Не объявлять late/timely автоматически. |
| Подтверждение WIRE return | Уникальная исходящая bank entry/reference, исходный payer, amount/currency, дата и evidence hash. Решение/поручение/отправка отдельно от CONFIRMED. |
| PSP и WIRE одновременно | Lock invoice/allocations, уникальный coverage effect; второй источник сохраняется UNALLOCATED. Возврат исходит только из остатка соответствующего payment source. |
| Refund + chargeback | Сначала authoritative reconciliation, проверить reserved и confirmed returned totals; один денежный intent на остаток. Новое подтверждение внешнего факта не вызывает дополнительного расхода. |
| Недоплата/late first/late renewal/excess | Точные алгоритмы [L06](state-machines.md#l06-нормализованный-settlement-psp-и-wire) по D235–D239/D246/D247. Платёж 25 на invoice 20: покрытие 20, возврат всего излишка 5. |

Срок и комиссия исходящего возврата приняты в C05. Уже согласованное правило о комиссии **входящего** перевода за счёт покупателя не меняется.

## C05. Банковский возврат: 5 рабочих дней, исходящая комиссия Taska (D252 / O04)

Оператор должен принять решение и, при одобрении, **отправить** возврат в пределах одного срока — 5 рабочих дней после первого получения полного проверенного комплекта банковских данных. `decisionDueAt = sendDueAt`: одобрение не запускает ещё 5 дней. Отказ с обоснованием тоже фиксируется до decisionDueAt. `evidenceCompletedAt` устанавливает сервер при проверке обязательного набора, не пользователь или повтор команды. Переоткрытие, смена исполнителя, retry и новая версия календаря не обнуляют срок; просрочка остаётся видимой и вызывает alert.

Исходящую комиссию несёт Taska; сумма возвращаемого principal не уменьшается на неё. Время межбанковского зачисления показывается отдельно; `submittedAt` не означает CONFIRMED. Дополнительного OTP нет (D248). Внутренний SLA не отменяет более коротких применимых обязательных сроков: оператор исполняет их и сохраняет основание в case.

Техническая политика `WIRE_RETURN_ES_V1`: Europe/Madrid, отсчитать 5 рабочих **дат**, не считая локальную дату evidenceCompletedAt, сохранить местное время суток. Рабочие даты — понедельник–пятница за вычетом национальных и применимых региональных/местных банковских нерабочих дат из versioned календаря продавца. DST gap → первый допустимый instant, overlap → более поздний offset. Timestamp хранится UTC; версия tzdb, календаря и hash snapshot фиксируются при старте SLA. В sandbox только явно помеченный fixture; оператор подставляет действительный календарь при live подключении. Нельзя выдавать неполный календарь за действующий или обходить SLA вечным WAITING_EVIDENCE: нужны конкретные недостающие сведения и audit. Поля и инварианты — Refund DTO / refunds schema.

## C06. Политика хранения и документы после удаления принята по делегации (D253 / O09)

По ответу владельца «сам реши» принята [полная политика ES_V1](retention-policy.md) и [машиночитаемая матрица](schemas/retention-policy.json). Это выбор сроков продукта с отдельными юридическими основаниями, а не утверждение, что закон требует одинаково хранить все данные.

Основные сроки: рабочие данные/профиль — очистить из активных систем не позднее 30 дней после tombstone; backups — 35 дней от создания; финансовый архив — 6 лет от последней относящейся бухгалтерской записи с явно зарегистрированными statutory/claim holds; security audit — 1 год; обычные operational logs — 30 дней. Условные OSS записи — 10 лет с конца года операции, только если этот режим применим. Подробные start events, блокирование минимальных доказательств, raw payload, FREE marker, lawful erasure и access controls определены в матрице.

После удаления финансовые документы выдаются через проверенное обращение к оператору: доказать связь с покупателем/полномочия на компанию и право на конкретный документ; вход в удалённый аккаунт и memberships не восстанавливаются. Заблокированный профиль нельзя просматривать для обычного обслуживания. Рабочий контент компании через финансовый архив не выдаётся.

## Граница готовности разработки

TAS-518/519/520 — технические реализации готовых case workflow, live configuration и retention engine. C03/C05/C06 больше не являются ожидающими решениями владельца; Stripe выбран, фактическое подключение отложено по D250. Все правила, состояния, таблицы, DTO и критерии согласованы здесь и в связанных документах. Missing/corrupt configuration остаётся отрицательным тестом и ошибкой настройки, а не обычным состоянием «ждём бизнес-ответ». Реальная регистрация продавца, secrets и календарь — проверяемые входы будущего live запуска.

## Первичные источники

1. [Stripe Checkout](https://docs.stripe.com/payments/checkout).
2. [Stripe webhooks](https://docs.stripe.com/webhooks?locale=en-GB).
3. [Stripe API idempotency](https://docs.stripe.com/api/idempotent_requests).
4. [Adyen Pay by Link](https://docs.adyen.com/unified-commerce/pay-by-link).
5. [Adyen HMAC verification](https://docs.adyen.com/development-resources/webhooks/secure-webhooks/verify-hmac-signatures).
6. [Adyen API idempotency](https://docs.adyen.com/development-resources/api-idempotency/).
7. [Stripe refunds](https://docs.stripe.com/refunds).
8. [Adyen token payments](https://docs.adyen.com/online-payments/tokenization/make-token-payments).
9. [ECB reference exchange rates](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html).
10. [GDPR, Regulation (EU) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng).
